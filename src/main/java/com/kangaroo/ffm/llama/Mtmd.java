package com.kangaroo.ffm.llama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The multimodal projector, bound in-process.
 *
 * <p>This is the part the previous generation of this architecture could not do from Java at all.
 * The usual options are to shell out to a C++ helper through a hand-written JNI bridge, or to stand
 * up an HTTP server purely so that an image can be base64-encoded across a process boundary and
 * decoded again on the other side. Both are several hundred lines of glue whose only job is to move
 * bytes that never needed to move.
 *
 * <p>Here, {@link #evalWithImages} decodes the captured frame into an off-heap
 * {@link MemorySegment} exactly once and passes its address to {@code mtmd_helper_bitmap_init_from_buf}.
 * The projector reads the pixels where they already are. For a seven-capture CHW assessment on a
 * Raspberry Pi, avoiding fourteen copies of a multi-megabyte frame is worth more than any model
 * optimisation available to us.
 */
final class Mtmd implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker();

    private final Llama llama;
    private final Arena arena;

    private final MethodHandle contextParamsDefault;
    private final MethodHandle initFromFile;
    private final MethodHandle free;
    private final MethodHandle defaultMarker;
    private final MethodHandle supportVision;

    private final MethodHandle bitmapInitFromBuf;
    private final MethodHandle bitmapFree;

    private final MethodHandle chunksInit;
    private final MethodHandle chunksFree;
    private final MethodHandle tokenize;
    private final MethodHandle helperEvalChunks;

    private final MemorySegment context;
    private final String mediaMarker;

    static Mtmd open(Llama llama, SymbolLookup lookup, Arena arena, Path mmproj, Llama.Options options) {
        return new Mtmd(llama, lookup, arena, mmproj, options);
    }

    private Mtmd(Llama llama, SymbolLookup lookup, Arena arena, Path mmproj, Llama.Options options) {
        this.llama = llama;
        this.arena = arena;

        contextParamsDefault = bind(lookup, "mtmd_context_params_default",
                FunctionDescriptor.of(LlamaLayouts.MTMD_CONTEXT_PARAMS));
        initFromFile = bind(lookup, "mtmd_init_from_file",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, LlamaLayouts.MTMD_CONTEXT_PARAMS));
        free = bind(lookup, "mtmd_free", FunctionDescriptor.ofVoid(ADDRESS));
        defaultMarker = bind(lookup, "mtmd_default_marker", FunctionDescriptor.of(ADDRESS));
        supportVision = bind(lookup, "mtmd_support_vision", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS));

        bitmapInitFromBuf = bind(lookup, "mtmd_helper_bitmap_init_from_buf",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        bitmapFree = bind(lookup, "mtmd_bitmap_free", FunctionDescriptor.ofVoid(ADDRESS));

        chunksInit = bind(lookup, "mtmd_input_chunks_init", FunctionDescriptor.of(ADDRESS));
        chunksFree = bind(lookup, "mtmd_input_chunks_free", FunctionDescriptor.ofVoid(ADDRESS));
        tokenize = bind(lookup, "mtmd_tokenize",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        helperEvalChunks = bind(lookup, "mtmd_helper_eval_chunks",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS,
                        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_BOOLEAN, ADDRESS));

        // mtmd logs through its own callback, separate from llama's. Without this it writes its
        // per-slice encode timings straight to the console, past every logging setting the
        // application has.
        routeLogsThroughJava(lookup);

        this.context = init(mmproj, options);
        this.mediaMarker = readMarker();
    }

    private void routeLogsThroughJava(SymbolLookup lookup) {
        for (String name : List.of("mtmd_log_set", "mtmd_helper_log_set")) {
            lookup.find(name).ifPresent(addr -> {
                MethodHandle h = LINKER.downcallHandle(addr,
                        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
                llama.invokeVoid(h, llama.logBridgeStub(), MemorySegment.NULL);
            });
        }
    }

    private MethodHandle bind(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        MemorySegment addr = lookup.find(name).orElseThrow(() ->
                new IllegalStateException("multimodal symbol '" + name + "' not found; "
                        + "the mtmd library is present but is a different build than llama.cpp "
                        + Llama.TARGET_BUILD));
        return LINKER.downcallHandle(addr, descriptor);
    }

    private MemorySegment init(Path mmproj, Llama.Options options) {
        MemorySegment params = (MemorySegment) llama.invoke(contextParamsDefault, arena);
        Llama.varHandle(LlamaLayouts.MTMD_CONTEXT_PARAMS, "use_gpu")
                .set(params, 0L, options.gpuLayers() > 0);
        Llama.varHandle(LlamaLayouts.MTMD_CONTEXT_PARAMS, "print_timings").set(params, 0L, false);
        if (options.threads() > 0) {
            Llama.varHandle(LlamaLayouts.MTMD_CONTEXT_PARAMS, "n_threads").set(params, 0L, options.threads());
        }

        MemorySegment path = arena.allocateFrom(mmproj.toAbsolutePath().toString());
        MemorySegment ctx = llama.invokePointer(initFromFile, path, llama.modelHandle(), params);
        if (ctx.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("could not initialise the multimodal projector from " + mmproj);
        }
        if (!(boolean) llama.invoke(supportVision, ctx)) {
            llama.invokeVoid(free, ctx);
            throw new IllegalStateException("the projector at " + mmproj + " does not support vision");
        }
        return ctx;
    }

    private String readMarker() {
        MemorySegment p = llama.invokePointer(defaultMarker);
        return p.equals(MemorySegment.NULL) ? "<__media__>" : p.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /**
     * Tokenise the prompt together with its images and evaluate every resulting chunk.
     *
     * @param call   the per-generation arena; every native allocation below dies with it
     * @param prompt the prompt, with one media marker per image
     * @param images encoded frames, exactly as captured
     * @return the number of positions consumed, which becomes the generation's starting position
     */
    int evalWithImages(Arena call, String prompt, List<byte[]> images) {
        String withMarkers = ensureMarkers(prompt, images.size());

        MemorySegment chunks = llama.invokePointer(chunksInit);
        if (chunks.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("could not allocate multimodal input chunks");
        }

        List<MemorySegment> bitmaps = new java.util.ArrayList<>(images.size());
        try {
            // The single copy: encoded bytes go off-heap once, and the projector reads them there.
            for (byte[] image : images) {
                MemorySegment buf = call.allocateFrom(JAVA_BYTE, image);
                MemorySegment bitmap = llama.invokePointer(bitmapInitFromBuf, context, buf, (long) image.length);
                if (bitmap.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("mtmd could not decode a captured frame ("
                            + image.length + " bytes)");
                }
                bitmaps.add(bitmap);
            }

            MemorySegment bitmapArray = call.allocate(ADDRESS, bitmaps.size());
            for (int i = 0; i < bitmaps.size(); i++) {
                bitmapArray.setAtIndex(ADDRESS, i, bitmaps.get(i));
            }

            MemorySegment text = call.allocate(LlamaLayouts.MTMD_INPUT_TEXT);
            Llama.varHandle(LlamaLayouts.MTMD_INPUT_TEXT, "text")
                    .set(text, 0L, call.allocateFrom(withMarkers));
            Llama.varHandle(LlamaLayouts.MTMD_INPUT_TEXT, "add_special").set(text, 0L, true);
            Llama.varHandle(LlamaLayouts.MTMD_INPUT_TEXT, "parse_special").set(text, 0L, true);

            int rc = (int) llama.invoke(tokenize, context, chunks, text, bitmapArray, (long) bitmaps.size());
            if (rc != 0) {
                throw new IllegalStateException("mtmd_tokenize failed with code " + rc);
            }

            MemorySegment newNPast = call.allocate(LlamaLayouts.POS);
            int eval = (int) llama.invoke(helperEvalChunks,
                    context, llama.contextHandle(), chunks,
                    0,      // n_past
                    0,      // seq_id
                    512,    // n_batch
                    true,   // logits_last: we want to sample straight after the last chunk
                    newNPast);
            if (eval != 0) {
                throw new IllegalStateException("mtmd_helper_eval_chunks failed with code " + eval);
            }
            return newNPast.get(LlamaLayouts.POS, 0);

        } finally {
            for (MemorySegment bitmap : bitmaps) {
                llama.invokeVoid(bitmapFree, bitmap);
            }
            llama.invokeVoid(chunksFree, chunks);
        }
    }

    /**
     * Make sure the prompt carries exactly one media marker per image.
     *
     * <p>A prompt with the wrong number of markers is not an error mtmd reports helpfully — it
     * silently associates the wrong image with the wrong instruction, which in this application
     * means grading the umbilical stump photo for jaundice. Normalising here is cheap insurance.
     */
    private String ensureMarkers(String prompt, int imageCount) {
        long present = countOccurrences(prompt, mediaMarker);
        if (present == imageCount) return prompt;
        if (present > imageCount) {
            throw new IllegalArgumentException("prompt has " + present + " media markers but only "
                    + imageCount + " images were supplied");
        }
        StringBuilder sb = new StringBuilder(prompt);
        for (long i = present; i < imageCount; i++) {
            sb.append('\n').append(mediaMarker);
        }
        return sb.toString();
    }

    private static long countOccurrences(String haystack, String needle) {
        long n = 0;
        int at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }

    String mediaMarker() {
        return mediaMarker;
    }

    @Override
    public void close() {
        if (context != null && !context.equals(MemorySegment.NULL)) {
            llama.invokeVoid(free, context);
        }
    }
}
