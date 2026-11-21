package com.omni.llm.service

/**
 * Wire protocol between the UI process and the `:llm` inference process.
 *
 * Messenger + Bundle instead of AIDL: no codegen, no compile-time coupling, and the whole thing
 * is trivially inspectable in logcat when something goes wrong on a user's phone.
 */
object LlmProtocol {

    // Client -> service
    const val MSG_LOAD = 1
    const val MSG_GENERATE = 2
    const val MSG_CANCEL = 3
    const val MSG_UNLOAD = 4
    const val MSG_PING = 5
    const val MSG_RESET = 6
    const val MSG_SET_THREADS = 7
    const val MSG_COUNT_TOKENS = 8
    const val MSG_SAVE_KV = 9

    // Service -> client
    const val R_LOAD_PROGRESS = 101
    const val R_LOADED = 102
    const val R_TOKEN = 103
    const val R_DONE = 104
    const val R_ERROR = 105
    const val R_PONG = 106
    const val R_THERMAL = 107
    const val R_WARNING = 108
    const val R_TOKEN_COUNT = 109

    // Keys
    const val K_REQUEST_ID = "reqId"
    const val K_MODEL_PATH = "modelPath"
    const val K_MODEL_ID = "modelId"
    const val K_N_CTX = "nCtx"
    const val K_N_BATCH = "nBatch"
    const val K_N_UBATCH = "nUbatch"
    const val K_N_THREADS = "nThreads"
    const val K_GPU_LAYERS = "gpuLayers"
    const val K_USE_MMAP = "useMmap"
    const val K_USE_MLOCK = "useMlock"
    const val K_FLASH_ATTN = "flashAttn"
    const val K_Q8_KV = "q8kv"
    const val K_DRAFT_PATH = "draftPath"
    const val K_DRAFT_GPU_LAYERS = "draftGpuLayers"
    const val K_DRAFT_TOKENS = "draftTokens"

    const val K_ROLES = "roles"
    const val K_CONTENTS = "contents"
    const val K_MAX_TOKENS = "maxTokens"
    const val K_TEMPERATURE = "temperature"
    const val K_TOP_P = "topP"
    const val K_TOP_K = "topK"
    const val K_MIN_P = "minP"
    const val K_REPEAT_PENALTY = "repeatPenalty"
    const val K_SEED = "seed"

    const val K_PIECE = "piece"
    const val K_COUNT = "count"
    const val K_FRACTION = "fraction"
    const val K_STATUS = "status"
    const val K_ERROR = "error"
    const val K_JSON = "json"
    const val K_MESSAGE = "message"
    const val K_KV_PATH = "kvPath"
    const val K_TOKEN_COUNT = "tokenCount"
}
