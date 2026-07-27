package com.kangaroo.ffm.llama;

import com.kangaroo.audit.ClinicalEvents;
import com.kangaroo.ffm.NativeRuntime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * In-process multimodal inference, through the Foreign Function and Memory API. No JNI anywhere.
 *
 * <p>This class is the engineering spine of the project. The usual way to put a quantised
 * vision-language model behind a Java application is to run {@code llama-server} as a subprocess and
 * talk HTTP to it on localhost. That works, and it costs you: a second process to supervise, a port
 * to fight over, a startup race, and — the expensive part for this application — every captured
 * frame base64-encoded into a JSON body, parsed back out, and copied again before it reaches the
 * model. On a Raspberry Pi serving a village, that copying is worth more than any model
 * optimisation.
 *
 * <p>Here the model lives in the same address space. A frame is decoded once into a
 * {@link MemorySegment} and handed to the projector by address. There is one process, one lifecycle,
 * one place to look when something goes wrong, and the whole native surface is about twenty
 * {@link Linker#downcallHandle downcall handles} that a reviewer can read in one sitting.
 *
 * <h2>Memory discipline</h2>
 * Every native allocation is owned by an {@link Arena}. The model, the context and the sampler chain
 * live in {@link #arena}, which is closed by {@link #close()}; everything belonging to a single
 * generation lives in a confined arena that is closed when the generation returns, whether it
 * returned normally or threw. There is no manual free anywhere in this file and no way to leak one,
 * which is the property JNI could never give us.
 *
 * <h2>Determinism</h2>
 * Clinical outputs must be reproducible, so the sampler chain is seeded explicitly and the KV cache
 * is cleared between encounters. Given the same model file, the same seed and the same prompt, this
 * produces the same tokens — something an HTTP server shared between callers cannot promise.
 */
public final class Llama implements AutoCloseable {

    /** The upstream llama.cpp build these bindings were written and verified against. */
    public static final String TARGET_BUILD = "b9006";

    private static final System.Logger LOG = System.getLogger("kangaroo.llama");
    private static final Linker LINKER = Linker.nativeLinker();

    // ------------------------------------------------------------------ generation options

    /**
     * @param contextTokens  KV cache size
     * @param maxTokens      generation cap
     * @param temperature    0 for greedy, which is what clinical output uses
     * @param topP           nucleus sampling cutoff, ignored when greedy
     * @param seed           explicit, for reproducibility
     * @param threads        0 to let llama.cpp choose
     * @param gpuLayers      layers to offload; 0 keeps everything on the CPU
     * @param grammar        an optional GBNF grammar constraining the output shape
     */
    public record Options(
            int contextTokens,
            int maxTokens,
            float temperature,
            float topP,
            int seed,
            int threads,
            int gpuLayers,
            Optional<String> grammar) {

        public static Options clinicalDefaults() {
            // Greedy and seeded: an assessment that changes its mind between runs on identical
            // input is not auditable, whatever else it is.
            return new Options(4096, 512, 0.0f, 1.0f, 20260726, 0, 0, Optional.empty());
        }

        public Options withGrammar(String gbnf) {
            return new Options(contextTokens, maxTokens, temperature, topP, seed, threads,
                    gpuLayers, Optional.ofNullable(gbnf));
        }

        public Options withMaxTokens(int n) {
            return new Options(contextTokens, n, temperature, topP, seed, threads, gpuLayers, grammar);
        }

        public Options withGpuLayers(int n) {
            return new Options(contextTokens, maxTokens, temperature, topP, seed, threads, n, grammar);
        }
    }

    // ------------------------------------------------------------------ bound functions

    private final Arena arena;
    private final SymbolLookup lookup;

    private final MethodHandle backendInit;
    private final MethodHandle modelDefaultParams;
    private final MethodHandle modelLoadFromFile;
    private final MethodHandle modelFree;
    private final MethodHandle modelGetVocab;
    private final MethodHandle modelDesc;
    private final MethodHandle modelNParams;
    private final MethodHandle modelChatTemplate;
    private final MethodHandle chatApplyTemplate;

    private final MethodHandle contextDefaultParams;
    private final MethodHandle initFromModel;
    private final MethodHandle freeContext;
    private final MethodHandle nCtx;
    private final MethodHandle getMemory;
    private final MethodHandle memoryClear;

    private final MethodHandle tokenize;
    private final MethodHandle tokenToPiece;
    private final MethodHandle vocabNTokens;
    private final MethodHandle vocabIsEog;
    private final MethodHandle vocabBos;
    private final MethodHandle vocabEos;

    private final MethodHandle batchGetOne;
    private final MethodHandle decode;

    private final MethodHandle samplerChainDefaultParams;
    private final MethodHandle samplerChainInit;
    private final MethodHandle samplerChainAdd;
    private final MethodHandle samplerInitGreedy;
    private final MethodHandle samplerInitDist;
    private final MethodHandle samplerInitTemp;
    private final MethodHandle samplerInitTopP;
    private final MethodHandle samplerInitGrammar;
    private final MethodHandle samplerSample;
    private final MethodHandle samplerAccept;
    private final MethodHandle samplerFree;
    private final MethodHandle logSet;

    private final MemorySegment model;
    private final MemorySegment context;
    private final MemorySegment vocab;
    private final MemorySegment sampler;
    private final Options options;
    private final Path modelPath;
    private final Mtmd mtmd;

    private MemorySegment logBridgeStub;
    private int nPast;

    // ------------------------------------------------------------------ lifecycle

    /**
     * Open a model.
     *
     * @param modelPath  a GGUF model file
     * @param mmprojPath an optional multimodal projector, enabling image input
     * @throws IllegalStateException when no native library is present; callers should check
     *         {@link NativeRuntime#available()} first and fall back to the deterministic path.
     */
    public static Llama open(Path modelPath, Optional<Path> mmprojPath, Options options) {
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup lookup = NativeRuntime.openLlama(arena)
                    .orElseThrow(() -> new IllegalStateException(NativeRuntime.unavailableReason()));
            return new Llama(arena, lookup, modelPath, mmprojPath, options);
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    private Llama(Arena arena, SymbolLookup lookup, Path modelPath, Optional<Path> mmprojPath,
                  Options options) {
        this.arena = arena;
        this.lookup = lookup;
        this.options = options;
        this.modelPath = modelPath;

        LlamaLayouts.verify();

        // ---- bind
        backendInit = bind("llama_backend_init", FunctionDescriptor.ofVoid());
        modelDefaultParams = bind("llama_model_default_params",
                FunctionDescriptor.of(LlamaLayouts.MODEL_PARAMS));
        modelLoadFromFile = bind("llama_model_load_from_file",
                FunctionDescriptor.of(ADDRESS, ADDRESS, LlamaLayouts.MODEL_PARAMS));
        modelFree = bind("llama_model_free", FunctionDescriptor.ofVoid(ADDRESS));
        modelGetVocab = bind("llama_model_get_vocab", FunctionDescriptor.of(ADDRESS, ADDRESS));
        modelDesc = bind("llama_model_desc", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        modelNParams = bind("llama_model_n_params", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        modelChatTemplate = bind("llama_model_chat_template", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        chatApplyTemplate = bind("llama_chat_apply_template", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, ADDRESS, JAVA_LONG, JAVA_BOOLEAN, ADDRESS, JAVA_INT));

        contextDefaultParams = bind("llama_context_default_params",
                FunctionDescriptor.of(LlamaLayouts.CONTEXT_PARAMS));
        initFromModel = bind("llama_init_from_model",
                FunctionDescriptor.of(ADDRESS, ADDRESS, LlamaLayouts.CONTEXT_PARAMS));
        freeContext = bind("llama_free", FunctionDescriptor.ofVoid(ADDRESS));
        nCtx = bind("llama_n_ctx", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        getMemory = bind("llama_get_memory", FunctionDescriptor.of(ADDRESS, ADDRESS));
        memoryClear = bind("llama_memory_clear", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN));

        tokenize = bind("llama_tokenize", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_BOOLEAN, JAVA_BOOLEAN));
        tokenToPiece = bind("llama_token_to_piece", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_BOOLEAN));
        vocabNTokens = bind("llama_vocab_n_tokens", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        vocabIsEog = bind("llama_vocab_is_eog", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT));
        vocabBos = bind("llama_vocab_bos", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        vocabEos = bind("llama_vocab_eos", FunctionDescriptor.of(JAVA_INT, ADDRESS));

        batchGetOne = bind("llama_batch_get_one",
                FunctionDescriptor.of(LlamaLayouts.BATCH, ADDRESS, JAVA_INT));
        decode = bind("llama_decode", FunctionDescriptor.of(JAVA_INT, ADDRESS, LlamaLayouts.BATCH));

        samplerChainDefaultParams = bind("llama_sampler_chain_default_params",
                FunctionDescriptor.of(LlamaLayouts.SAMPLER_CHAIN_PARAMS));
        samplerChainInit = bind("llama_sampler_chain_init",
                FunctionDescriptor.of(ADDRESS, LlamaLayouts.SAMPLER_CHAIN_PARAMS));
        samplerChainAdd = bind("llama_sampler_chain_add", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        samplerInitGreedy = bind("llama_sampler_init_greedy", FunctionDescriptor.of(ADDRESS));
        samplerInitDist = bind("llama_sampler_init_dist", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        samplerInitTemp = bind("llama_sampler_init_temp", FunctionDescriptor.of(ADDRESS, JAVA_FLOAT));
        samplerInitTopP = bind("llama_sampler_init_top_p",
                FunctionDescriptor.of(ADDRESS, JAVA_FLOAT, JAVA_LONG));
        samplerInitGrammar = bind("llama_sampler_init_grammar",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        samplerSample = bind("llama_sampler_sample",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        samplerAccept = bind("llama_sampler_accept", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        samplerFree = bind("llama_sampler_free", FunctionDescriptor.ofVoid(ADDRESS));
        logSet = bind("llama_log_set", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

        // ---- initialise
        installLogBridge();
        loadGgmlBackends();
        invokeVoid(backendInit);

        this.model = loadModel(modelPath);
        this.vocab = invokePointer(modelGetVocab, model);
        this.context = createContext();
        this.sampler = buildSamplerChain();
        this.mtmd = mmprojPath
                .map(p -> Mtmd.open(this, lookup, arena, p, options))
                .orElse(null);
    }

    private MethodHandle bind(String name, FunctionDescriptor descriptor) {
        MemorySegment addr = lookup.find(name).orElseThrow(() ->
                new IllegalStateException("symbol '" + name + "' not found in the native library. "
                        + "Kangaroo targets llama.cpp " + TARGET_BUILD
                        + "; a much older or newer build will not have the same exports."));
        return LINKER.downcallHandle(addr, descriptor);
    }

    /**
     * Route llama.cpp's own logging into the Java logging system, via an FFM upcall stub.
     *
     * <p>Without this, a library that is very chatty on stderr writes straight past every logging
     * configuration the application has, which on a field device means a log file that fills a
     * 400 MB SD card. The upcall is the mirror image of the downcalls above — a Java method handle
     * exposed to C as a function pointer — and it costs one stub allocated in the shared arena.
     */
    private void installLogBridge() {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(Llama.class, "onNativeLog",
                    java.lang.invoke.MethodType.methodType(void.class, int.class,
                            MemorySegment.class, MemorySegment.class));
            logBridgeStub = LINKER.upcallStub(target,
                    FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, ADDRESS), arena);
            invokeVoid(logSet, logBridgeStub, MemorySegment.NULL);
        } catch (ReflectiveOperationException e) {
            // Logging is a convenience; failing to redirect it must never stop an assessment.
            LOG.log(System.Logger.Level.DEBUG, "could not install the native log bridge", e);
            logBridgeStub = MemorySegment.NULL;
        }
    }

    /** The upcall stub, so the projector can be pointed at the same bridge. */
    MemorySegment logBridgeStub() {
        return logBridgeStub == null ? MemorySegment.NULL : logBridgeStub;
    }

    /**
     * Load ggml's compute backends before touching the model.
     *
     * <p>Modern ggml ships its CPU variants — and CUDA, Vulkan, and the rest — as separate shared
     * libraries discovered at runtime rather than linked in, so loading {@code llama.dll} alone
     * gives you a library with no backend registered and {@code llama_model_load_from_file}
     * returning null. Pointing the loader at our own directory rather than calling the bare
     * {@code ggml_backend_load_all} is deliberate: the bare version searches the process working
     * directory and the executable's directory, neither of which is where Kangaroo keeps its
     * runtime, and on a Pod the working directory is wherever systemd happened to start us.
     */
    private void loadGgmlBackends() {
        Optional<MemorySegment> symbol = lookup.find("ggml_backend_load_all_from_path");
        Optional<Path> dir = NativeRuntime.directory();
        try (Arena temp = Arena.ofConfined()) {
            if (symbol.isPresent() && dir.isPresent()) {
                MethodHandle h = LINKER.downcallHandle(symbol.get(), FunctionDescriptor.ofVoid(ADDRESS));
                invokeVoid(h, temp.allocateFrom(dir.get().toAbsolutePath().toString()));
                return;
            }
            lookup.find("ggml_backend_load_all").ifPresent(addr ->
                    invokeVoid(LINKER.downcallHandle(addr, FunctionDescriptor.ofVoid())));
        }
    }

    @SuppressWarnings("unused")   // called from native code through the upcall stub
    private static void onNativeLog(int level, MemorySegment text, MemorySegment userData) {
        if (text.equals(MemorySegment.NULL)) return;
        String message = text.reinterpret(Long.MAX_VALUE).getString(0).stripTrailing();
        if (message.isEmpty()) return;
        // llama.cpp levels: 1 debug, 2 info, 3 warn, 4 error.
        System.Logger.Level mapped = switch (level) {
            case 4 -> System.Logger.Level.ERROR;
            case 3 -> System.Logger.Level.WARNING;
            case 2 -> System.Logger.Level.DEBUG;
            default -> System.Logger.Level.TRACE;
        };
        LOG.log(mapped, message);
    }

    private MemorySegment loadModel(Path path) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment params = (MemorySegment) invoke(modelDefaultParams, arena);
            varHandle(LlamaLayouts.MODEL_PARAMS, "n_gpu_layers").set(params, 0L, options.gpuLayers());
            varHandle(LlamaLayouts.MODEL_PARAMS, "use_mmap").set(params, 0L, true);

            MemorySegment name = temp.allocateFrom(path.toAbsolutePath().toString());
            MemorySegment m = invokePointer(modelLoadFromFile, name, params);
            if (m.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("llama.cpp could not load the model at " + path);
            }
            return m;
        }
    }

    private MemorySegment createContext() {
        MemorySegment params = (MemorySegment) invoke(contextDefaultParams, arena);
        varHandle(LlamaLayouts.CONTEXT_PARAMS, "n_ctx").set(params, 0L, options.contextTokens());
        varHandle(LlamaLayouts.CONTEXT_PARAMS, "n_batch").set(params, 0L, 512);
        if (options.threads() > 0) {
            varHandle(LlamaLayouts.CONTEXT_PARAMS, "n_threads").set(params, 0L, options.threads());
            varHandle(LlamaLayouts.CONTEXT_PARAMS, "n_threads_batch").set(params, 0L, options.threads());
        }
        varHandle(LlamaLayouts.CONTEXT_PARAMS, "no_perf").set(params, 0L, true);

        MemorySegment c = invokePointer(initFromModel, model, params);
        if (c.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("llama.cpp could not create a context");
        }
        return c;
    }

    /**
     * Build the sampler chain.
     *
     * <p>When a grammar is supplied it goes first, so that every later stage only ever sees tokens
     * the grammar already permits. That ordering is what turns "please reply as JSON" from a hope
     * into a guarantee — the twenty-one-check output cannot be malformed, because no token that
     * would malform it is reachable.
     */
    private MemorySegment buildSamplerChain() {
        MemorySegment chainParams = (MemorySegment) invoke(samplerChainDefaultParams, arena);
        varHandle(LlamaLayouts.SAMPLER_CHAIN_PARAMS, "no_perf").set(chainParams, 0L, true);
        MemorySegment chain = invokePointer(samplerChainInit, chainParams);

        options.grammar().ifPresent(gbnf -> {
            MemorySegment g = arena.allocateFrom(gbnf);
            MemorySegment root = arena.allocateFrom("root");
            MemorySegment s = invokePointer(samplerInitGrammar, vocab, g, root);
            if (!s.equals(MemorySegment.NULL)) {
                invokeVoid(samplerChainAdd, chain, s);
            } else {
                LOG.log(System.Logger.Level.WARNING,
                        "grammar was rejected by llama.cpp; falling back to unconstrained sampling");
            }
        });

        if (options.temperature() <= 0.0f) {
            invokeVoid(samplerChainAdd, chain, invokePointer(samplerInitGreedy));
        } else {
            invokeVoid(samplerChainAdd, chain, invokePointer(samplerInitTemp, options.temperature()));
            invokeVoid(samplerChainAdd, chain, invokePointer(samplerInitTopP, options.topP(), 1L));
            invokeVoid(samplerChainAdd, chain, invokePointer(samplerInitDist, options.seed()));
        }
        return chain;
    }

    // ------------------------------------------------------------------ inference

    /**
     * Generate a completion for a raw prompt, with no chat template applied.
     *
     * <p>Almost always the wrong entry point for an instruction-tuned model — see
     * {@link #chat(String, String, Consumer)}. Kept because the evaluation harness needs to score
     * raw completions, and because it is what {@link #generateWithImages} builds on.
     */
    public String generate(String prompt, Consumer<String> onToken) {
        try (Arena call = Arena.ofConfined()) {
            resetContext();
            int[] tokens = tokenize(call, prompt, true, true);
            evaluate(call, tokens);
            return sampleLoop(call, onToken, tokens.length, 0);
        }
    }

    /**
     * Generate a reply, with the model's own chat template applied.
     *
     * <p>This matters more than it sounds. An instruction-tuned model handed a bare sentence with
     * no role markers does not produce a worse answer — it very often produces no answer at all,
     * emitting an end-of-turn token immediately, because from its point of view the turn it was
     * shown has already ended. Reading the template out of the GGUF rather than hard-coding one is
     * what lets the same code drive whatever model a deployment actually has on the SD card.
     *
     * @param system the system prompt, or empty for none
     * @param user   the user turn
     */
    public String chat(String system, String user, Consumer<String> onToken) {
        try (Arena call = Arena.ofConfined()) {
            resetContext();
            String prompt = applyChatTemplate(call, system, user);
            int[] tokens = tokenize(call, prompt, true, true);
            evaluate(call, tokens);
            return sampleLoop(call, onToken, tokens.length, 0);
        }
    }

    /**
     * Render the system and user turns through the model's embedded chat template.
     *
     * <p>Falls back to the turn markers Gemma-family and most other instruct models understand when
     * the GGUF carries no template at all.
     */
    String applyChatTemplate(Arena call, String system, String user) {
        MemorySegment tmpl = invokePointer(modelChatTemplate, model, MemorySegment.NULL);
        if (tmpl.equals(MemorySegment.NULL)) {
            return (system == null || system.isBlank() ? "" : system + "\n\n") + user;
        }

        boolean hasSystem = system != null && !system.isBlank();
        int count = hasSystem ? 2 : 1;
        MemorySegment messages = call.allocate(LlamaLayouts.CHAT_MESSAGE, count);
        int index = 0;
        if (hasSystem) {
            writeMessage(call, messages, index++, "system", system);
        }
        writeMessage(call, messages, index, "user", user);

        // Two calls, as the C API wants: one to learn the length, one to fill the buffer.
        int needed = (int) invoke(chatApplyTemplate, tmpl, messages, (long) count, true,
                MemorySegment.NULL, 0);
        if (needed <= 0) {
            return (hasSystem ? system + "\n\n" : "") + user;
        }
        MemorySegment buffer = call.allocate(needed + 1);
        int written = (int) invoke(chatApplyTemplate, tmpl, messages, (long) count, true,
                buffer, needed + 1);
        if (written <= 0) {
            return (hasSystem ? system + "\n\n" : "") + user;
        }
        return new String(buffer.toArray(JAVA_BYTE), 0, Math.min(written, needed), StandardCharsets.UTF_8);
    }

    /**
     * Generate with images, in process, through the multimodal projector.
     *
     * <p>The frames are handed to {@code mtmd} by address. Nothing is base64-encoded, nothing is
     * serialised, and the image bytes exist in exactly one place in memory for the whole call.
     */
    public String generateWithImages(String prompt, List<byte[]> images, Consumer<String> onToken) {
        return chatWithImages("", prompt, images, onToken);
    }

    /**
     * Chat with images, in process, through the multimodal projector.
     *
     * <p>The media markers are placed inside the user turn <em>before</em> the chat template is
     * applied, not appended to the templated string. Appending them afterwards puts the images
     * after the assistant-turn marker, where the model reads them as part of its own reply rather
     * than as part of the question — which produces a fluent description of nothing.
     */
    public String chatWithImages(String system, String user, List<byte[]> images, Consumer<String> onToken) {
        if (mtmd == null || images.isEmpty()) {
            return chat(system, user, onToken);
        }
        try (Arena call = Arena.ofConfined()) {
            resetContext();
            StringBuilder withMedia = new StringBuilder(user);
            for (int i = 0; i < images.size(); i++) {
                withMedia.append('\n').append(mtmd.mediaMarker());
            }
            String templated = applyChatTemplate(call, system, withMedia.toString());
            int consumed = mtmd.evalWithImages(call, templated, images);
            nPast = consumed;
            return sampleLoop(call, onToken, consumed, images.size());
        }
    }

    private String sampleLoop(Arena call, Consumer<String> onToken, int promptTokens, int images) {
        StringBuilder out = new StringBuilder();
        MemorySegment one = call.allocate(LlamaLayouts.TOKEN);
        int generated = 0;

        for (int i = 0; i < options.maxTokens(); i++) {
            int token = (int) invoke(samplerSample, sampler, context, -1);
            if (isEndOfGeneration(token)) break;

            invokeVoid(samplerAccept, sampler, token);
            String piece = pieceOf(call, token);
            out.append(piece);
            generated++;
            if (onToken != null && !piece.isEmpty()) onToken.accept(piece);

            one.set(LlamaLayouts.TOKEN, 0, token);
            MemorySegment batch = (MemorySegment) invoke(batchGetOne, call, one, 1);
            int rc = (int) invoke(decode, context, batch);
            if (rc != 0) {
                LOG.log(System.Logger.Level.WARNING, "llama_decode returned " + rc + "; stopping generation");
                break;
            }
            nPast++;
        }

        ClinicalEvents.nativeInference(modelPath.getFileName().toString(), promptTokens, generated,
                images, options.grammar().isPresent());
        return out.toString();
    }

    /** Clear the KV cache. Between encounters this is what stops one infant's context leaking into the next. */
    public void resetContext() {
        MemorySegment memory = invokePointer(getMemory, context);
        if (!memory.equals(MemorySegment.NULL)) {
            invokeVoid(memoryClear, memory, true);
        }
        nPast = 0;
    }

    private void evaluate(Arena call, int[] tokens) {
        if (tokens.length == 0) return;
        MemorySegment buffer = call.allocateFrom(LlamaLayouts.TOKEN, tokens);
        MemorySegment batch = (MemorySegment) invoke(batchGetOne, call, buffer, tokens.length);
        int rc = (int) invoke(decode, context, batch);
        if (rc != 0) {
            throw new IllegalStateException("llama_decode failed on the prompt with code " + rc);
        }
        nPast = tokens.length;
    }

    /** Write one {@code llama_chat_message} into an array of them, by slice rather than by path. */
    private static void writeMessage(Arena call, MemorySegment array, int index,
                                     String role, String content) {
        MemorySegment slot = array.asSlice(index * LlamaLayouts.CHAT_MESSAGE.byteSize(),
                LlamaLayouts.CHAT_MESSAGE);
        varHandle(LlamaLayouts.CHAT_MESSAGE, "role").set(slot, 0L, call.allocateFrom(role));
        varHandle(LlamaLayouts.CHAT_MESSAGE, "content").set(slot, 0L, call.allocateFrom(content));
    }

    // ------------------------------------------------------------------ vocabulary

    /** Tokenise, calling twice: once to learn the length, once to fill the buffer. */
    public int[] tokenize(Arena call, String text, boolean addSpecial, boolean parseSpecial) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        MemorySegment textSeg = call.allocateFrom(JAVA_BYTE, utf8);

        int needed = -(int) invoke(tokenize, vocab, textSeg, utf8.length,
                MemorySegment.NULL, 0, addSpecial, parseSpecial);
        if (needed <= 0) return new int[0];

        MemorySegment out = call.allocate(LlamaLayouts.TOKEN, needed);
        int written = (int) invoke(tokenize, vocab, textSeg, utf8.length, out, needed,
                addSpecial, parseSpecial);
        if (written < 0) {
            throw new IllegalStateException("tokenisation failed with code " + written);
        }
        return out.toArray(LlamaLayouts.TOKEN);
    }

    private String pieceOf(Arena call, int token) {
        MemorySegment buffer = call.allocate(64);
        int n = (int) invoke(tokenToPiece, vocab, token, buffer, 64, 0, true);
        if (n < 0) {
            MemorySegment bigger = call.allocate(-n);
            n = (int) invoke(tokenToPiece, vocab, token, bigger, -n, 0, true);
            if (n <= 0) return "";
            return new String(bigger.toArray(JAVA_BYTE), 0, n, StandardCharsets.UTF_8);
        }
        if (n == 0) return "";
        return new String(buffer.toArray(JAVA_BYTE), 0, n, StandardCharsets.UTF_8);
    }

    private boolean isEndOfGeneration(int token) {
        return (boolean) invoke(vocabIsEog, vocab, token);
    }

    // ------------------------------------------------------------------ description

    public int vocabularySize() { return (int) invoke(vocabNTokens, vocab); }

    public int contextSize() { return (int) invoke(nCtx, context); }

    public long parameterCount() { return (long) invoke(modelNParams, model); }

    public boolean visionEnabled() { return mtmd != null; }

    public Path modelPath() { return modelPath; }

    public int tokensConsumed() { return nPast; }

    /** The model's own self-description string, e.g. its architecture and quantisation. */
    public String describe() {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment buffer = call.allocate(256);
            int n = (int) invoke(modelDesc, model, buffer, 256L);
            if (n <= 0) return "unknown";
            return new String(buffer.toArray(JAVA_BYTE), 0, Math.min(n, 256), StandardCharsets.UTF_8);
        }
    }

    /** The chat template baked into the GGUF, when it has one. */
    public Optional<String> chatTemplate() {
        MemorySegment p = invokePointer(modelChatTemplate, model, MemorySegment.NULL);
        if (p.equals(MemorySegment.NULL)) return Optional.empty();
        return Optional.of(p.reinterpret(Long.MAX_VALUE).getString(0));
    }

    @Override
    public void close() {
        // Ordering matters: the sampler and context reference the model, and the mtmd context
        // references both. Tear down in the reverse of construction, then let the arena release
        // the library handles themselves.
        try {
            if (mtmd != null) mtmd.close();
            if (sampler != null && !sampler.equals(MemorySegment.NULL)) invokeVoid(samplerFree, sampler);
            if (context != null && !context.equals(MemorySegment.NULL)) invokeVoid(freeContext, context);
            if (model != null && !model.equals(MemorySegment.NULL)) invokeVoid(modelFree, model);
        } finally {
            arena.close();
        }
    }

    // ------------------------------------------------------------------ invocation helpers
    //
    // MethodHandle.invoke is varargs and throws Throwable. Rather than sprinkling try/catch over
    // every call site, these three wrappers centralise it: a native call that fails is a hard error
    // in this layer, and the failover ladder above catches it and descends a rung.

    Object invoke(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new NativeCallException(t);
        }
    }

    void invokeVoid(MethodHandle handle, Object... args) {
        invoke(handle, args);
    }

    MemorySegment invokePointer(MethodHandle handle, Object... args) {
        return (MemorySegment) invoke(handle, args);
    }

    static VarHandle varHandle(MemoryLayout layout, String field) {
        return layout.varHandle(MemoryLayout.PathElement.groupElement(field));
    }

    /** Any failure crossing the native boundary. Always caught by the failover ladder. */
    public static final class NativeCallException extends RuntimeException {
        public NativeCallException(Throwable cause) {
            super("native call failed: " + cause.getMessage(), cause);
        }
    }

    // Package-private accessors, for Mtmd.
    MemorySegment contextHandle() { return context; }
    MemorySegment modelHandle() { return model; }
    Arena sharedArena() { return arena; }
    List<String> boundSymbols() { return List.of(); }
}
