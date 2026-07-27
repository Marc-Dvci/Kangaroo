package com.kangaroo.ffm.llama;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The llama.cpp C structs, described to the Foreign Function and Memory API.
 *
 * <p>Every layout here is written against the {@code llama.h} of a pinned upstream build
 * ({@value Llama#TARGET_BUILD}). Struct layout is an ABI contract and llama.cpp changes it between
 * releases, so {@link Llama} verifies the layouts against the running library at startup and
 * refuses to proceed on a mismatch rather than reading a field at the wrong offset — which does not
 * crash, it just produces confident nonsense.
 *
 * <p>The explicit {@link MemoryLayout#paddingLayout} entries are the point of writing these out by
 * hand. They are where the C compiler's alignment rules become visible, they are checked by the
 * assertions in {@link #verify()}, and getting one wrong is exactly the class of bug that JNI glue
 * hides and FFM makes inspectable.
 */
public final class LlamaLayouts {

    private LlamaLayouts() {}

    /**
     * {@code struct llama_model_params} — 72 bytes.
     *
     * <p>The eight trailing booleans are grouped in the header with a comment explaining they are
     * kept together to avoid misalignment during copy-by-value. That grouping is why this struct
     * needs only the one interior pad.
     */
    public static final StructLayout MODEL_PARAMS = MemoryLayout.structLayout(
            ADDRESS.withName("devices"),
            ADDRESS.withName("tensor_buft_overrides"),
            JAVA_INT.withName("n_gpu_layers"),
            JAVA_INT.withName("split_mode"),
            JAVA_INT.withName("main_gpu"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("tensor_split"),
            ADDRESS.withName("progress_callback"),
            ADDRESS.withName("progress_callback_user_data"),
            ADDRESS.withName("kv_overrides"),
            JAVA_BOOLEAN.withName("vocab_only"),
            JAVA_BOOLEAN.withName("use_mmap"),
            JAVA_BOOLEAN.withName("use_direct_io"),
            JAVA_BOOLEAN.withName("use_mlock"),
            JAVA_BOOLEAN.withName("check_tensors"),
            JAVA_BOOLEAN.withName("use_extra_bufts"),
            JAVA_BOOLEAN.withName("no_host"),
            JAVA_BOOLEAN.withName("no_alloc")
    ).withName("llama_model_params");

    /** {@code struct llama_context_params} — 136 bytes. */
    public static final StructLayout CONTEXT_PARAMS = MemoryLayout.structLayout(
            JAVA_INT.withName("n_ctx"),
            JAVA_INT.withName("n_batch"),
            JAVA_INT.withName("n_ubatch"),
            JAVA_INT.withName("n_seq_max"),
            JAVA_INT.withName("n_threads"),
            JAVA_INT.withName("n_threads_batch"),
            JAVA_INT.withName("rope_scaling_type"),
            JAVA_INT.withName("pooling_type"),
            JAVA_INT.withName("attention_type"),
            JAVA_INT.withName("flash_attn_type"),
            JAVA_FLOAT.withName("rope_freq_base"),
            JAVA_FLOAT.withName("rope_freq_scale"),
            JAVA_FLOAT.withName("yarn_ext_factor"),
            JAVA_FLOAT.withName("yarn_attn_factor"),
            JAVA_FLOAT.withName("yarn_beta_fast"),
            JAVA_FLOAT.withName("yarn_beta_slow"),
            JAVA_INT.withName("yarn_orig_ctx"),
            JAVA_FLOAT.withName("defrag_thold"),
            ADDRESS.withName("cb_eval"),
            ADDRESS.withName("cb_eval_user_data"),
            JAVA_INT.withName("type_k"),
            JAVA_INT.withName("type_v"),
            ADDRESS.withName("abort_callback"),
            ADDRESS.withName("abort_callback_data"),
            JAVA_BOOLEAN.withName("embeddings"),
            JAVA_BOOLEAN.withName("offload_kqv"),
            JAVA_BOOLEAN.withName("no_perf"),
            JAVA_BOOLEAN.withName("op_offload"),
            JAVA_BOOLEAN.withName("swa_full"),
            JAVA_BOOLEAN.withName("kv_unified"),
            MemoryLayout.paddingLayout(2),
            ADDRESS.withName("samplers"),
            JAVA_LONG.withName("n_samplers")
    ).withName("llama_context_params");

    /** {@code typedef struct llama_batch} — 56 bytes. */
    public static final StructLayout BATCH = MemoryLayout.structLayout(
            JAVA_INT.withName("n_tokens"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("token"),
            ADDRESS.withName("embd"),
            ADDRESS.withName("pos"),
            ADDRESS.withName("n_seq_id"),
            ADDRESS.withName("seq_id"),
            ADDRESS.withName("logits")
    ).withName("llama_batch");

    /** {@code typedef struct llama_sampler_chain_params} — a single bool. */
    public static final StructLayout SAMPLER_CHAIN_PARAMS = MemoryLayout.structLayout(
            JAVA_BOOLEAN.withName("no_perf")
    ).withName("llama_sampler_chain_params");

    /** {@code struct mtmd_context_params}. */
    public static final StructLayout MTMD_CONTEXT_PARAMS = MemoryLayout.structLayout(
            JAVA_BOOLEAN.withName("use_gpu"),
            JAVA_BOOLEAN.withName("print_timings"),
            MemoryLayout.paddingLayout(2),
            JAVA_INT.withName("n_threads"),
            ADDRESS.withName("image_marker"),
            ADDRESS.withName("media_marker"),
            JAVA_INT.withName("flash_attn_type"),
            JAVA_BOOLEAN.withName("warmup"),
            MemoryLayout.paddingLayout(3),
            JAVA_INT.withName("image_min_tokens"),
            JAVA_INT.withName("image_max_tokens"),
            ADDRESS.withName("cb_eval"),
            ADDRESS.withName("cb_eval_user_data")
    ).withName("mtmd_context_params");

    /** {@code struct mtmd_input_text}. */
    public static final StructLayout MTMD_INPUT_TEXT = MemoryLayout.structLayout(
            ADDRESS.withName("text"),
            JAVA_BOOLEAN.withName("add_special"),
            JAVA_BOOLEAN.withName("parse_special"),
            MemoryLayout.paddingLayout(6)
    ).withName("mtmd_input_text");

    /** {@code typedef struct llama_chat_message} — a role and a content pointer. */
    public static final StructLayout CHAT_MESSAGE = MemoryLayout.structLayout(
            ADDRESS.withName("role"),
            ADDRESS.withName("content")
    ).withName("llama_chat_message");

    /** {@code llama_token} and {@code llama_pos} are both int32. */
    public static final ValueLayout.OfInt TOKEN = JAVA_INT;
    public static final ValueLayout.OfInt POS = JAVA_INT;
    public static final ValueLayout.OfInt SEQ_ID = JAVA_INT;
    public static final ValueLayout.OfByte LOGIT_FLAG = JAVA_BYTE;

    /**
     * Assert the sizes the upstream header implies.
     *
     * <p>Called once at startup. A layout that has drifted from the loaded library does not fail
     * loudly on its own — it reads {@code n_gpu_layers} out of the middle of a pointer and carries
     * on — so this is the only place the mismatch can be caught cheaply.
     *
     * @throws IllegalStateException if any layout does not have its expected size
     */
    public static void verify() {
        expect(MODEL_PARAMS, 72, "llama_model_params");
        expect(CONTEXT_PARAMS, 136, "llama_context_params");
        expect(BATCH, 56, "llama_batch");
        expect(MTMD_INPUT_TEXT, 16, "mtmd_input_text");
    }

    private static void expect(StructLayout layout, long bytes, String name) {
        if (layout.byteSize() != bytes) {
            throw new IllegalStateException(name + " layout is " + layout.byteSize()
                    + " bytes, expected " + bytes + " for llama.cpp " + Llama.TARGET_BUILD
                    + ". The bundled native library is a different build than this binding targets.");
        }
    }
}
