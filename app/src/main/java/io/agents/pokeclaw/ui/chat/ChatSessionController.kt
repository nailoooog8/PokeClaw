// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.agents.pokeclaw.agent.ModelPricing
import io.agents.pokeclaw.agent.llm.LlmClient
import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.agent.llm.LocalModelManager
import io.agents.pokeclaw.agent.llm.LocalModelRuntime
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.utils.XLog
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * Compose adapter for [ChatSessionSink].
 *
 * Every member delegates to the same Compose state the controller wrote before
 * the extraction, so the chat screen is behaviour-preserving by construction —
 * this is a type change, not a logic change.
 */
class ChatSessionUiState(
    val messages: SnapshotStateList<ChatMessage>,
    private val modelStatusState: MutableState<String>,
    private val awaitingReplyState: MutableState<Boolean>,
    private val inputEnabledState: MutableState<Boolean>,
    private val downloadingState: MutableState<Boolean>,
    private val downloadProgressState: MutableState<Int>,
    private val sessionTokensState: MutableState<Int>,
    private val sessionCostState: MutableState<Double>,
) : ChatSessionSink {

    // Names deliberately differ from the [ChatSessionSink] members: an `override
    // var modelStatus` next to a `MutableState<String> modelStatus` parameter
    // would be the same identifier twice with different meanings. Hence the
    // `…State` suffix, matching `TaskFlowUiState`.

    override var modelStatus: String
        get() = modelStatusState.value
        set(value) { modelStatusState.value = value }

    override var awaitingReply: Boolean
        get() = awaitingReplyState.value
        set(value) { awaitingReplyState.value = value }

    override var inputEnabled: Boolean
        get() = inputEnabledState.value
        set(value) { inputEnabledState.value = value }

    override var downloading: Boolean
        get() = downloadingState.value
        set(value) { downloadingState.value = value }

    override var downloadProgress: Int
        get() = downloadProgressState.value
        set(value) { downloadProgressState.value = value }

    override var sessionTokens: Int
        get() = sessionTokensState.value
        set(value) { sessionTokensState.value = value }

    override var sessionCost: Double
        get() = sessionCostState.value
        set(value) { sessionCostState.value = value }

    override fun append(message: ChatMessage) {
        messages.add(message)
    }

    override fun replaceTypingIndicator(text: String, modelName: String?) {
        val idx = messages.indexOfLast { isTypingIndicator(it) }
        if (idx >= 0) {
            messages[idx] = ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelName)
        } else {
            // Append, unlike `ChatSink`. The controller clears `awaitingReply`
            // before this runs, so dropping the reply here would strand the user.
            messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelName))
        }
    }

    override fun lastMessage(): ChatMessage? = messages.lastOrNull()

    override fun snapshotMessages(): List<ChatMessage> = messages.toList()

    private fun isTypingIndicator(m: ChatMessage) =
        m.role == ChatMessage.Role.ASSISTANT && m.content == ChatSink.TYPING_INDICATOR
}

class ChatSessionController(
    private val activity: ComponentActivity,
    private val executor: ExecutorService,
    private val sink: ChatSessionSink,
    private val onPersistConversation: () -> Unit,
    private val onRefreshSidebarHistory: () -> Unit,
    private val isTaskRunning: () -> Boolean,
) {

    companion object {
        private const val TAG = "ChatSessionController"
        private const val BASE_SYSTEM_PROMPT = "You are a helpful AI assistant on an Android phone."
    }

    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private var conversation: Conversation? = null
    private var isModelReady = false

    private var cloudClient: LlmClient? = null
    private var cloudModelName: String? = null
    private val cloudHistory = mutableListOf<dev.langchain4j.data.message.ChatMessage>()
    private var localUiGeneration: Long = 0
    private var suppressNextCloudSwitchMessage: Boolean = false

    fun isModelReady(): Boolean = isModelReady

    fun loadModelIfReady(
        conversationId: String? = null,
        visibleMessages: List<ChatMessage> = emptyList(),
    ) {
        val resolvedConfig = ModelConfigRepository.snapshot()

        if (!resolvedConfig.isLocalActive()) {
            localUiGeneration++
            val cloudConfig = resolvedConfig.activeCloud
            if (cloudConfig.apiKey.isNotEmpty() && cloudConfig.modelName.isNotEmpty()) {
                val previousModel = cloudModelName
                cloudClient = LlmSessionManager.createCloudClient(temperature = 0.7)
                if (cloudClient == null) {
                    sink.modelStatus = "No model selected"
                    isModelReady = false
                    setButtonsEnabled(false)
                    return
                }
                cloudModelName = cloudConfig.modelName
                if (previousModel == null || cloudHistory.isEmpty()) {
                    rebuildCloudHistoryFromVisibleMessages()
                } else if (previousModel != cloudConfig.modelName) {
                    cloudHistory.add(
                        SystemMessage.from(
                            "The user has switched from $previousModel to ${cloudConfig.modelName}. Continue the conversation naturally."
                        )
                    )
                    if (suppressNextCloudSwitchMessage) {
                        suppressNextCloudSwitchMessage = false
                    } else {
                        addSystem("Switched to ${cloudConfig.modelName}")
                    }
                }
                isModelReady = true
                sink.modelStatus = "● ${cloudConfig.modelName} · Cloud"
                setButtonsEnabled(true)
                XLog.i(TAG, "Cloud chat ready: ${cloudConfig.modelName} via ${cloudConfig.resolvedBaseUrl}")
            } else {
                sink.modelStatus = "No model selected"
                isModelReady = false
                setButtonsEnabled(false)
            }
            return
        }

        cloudClient = null
        val modelPath = resolvedConfig.local.modelPath
        if (isTaskRunning()) {
            sink.modelStatus = "● Local task using model"
            isModelReady = false
            setButtonsEnabled(false)
            return
        }
        XLog.d(TAG, "loadModelIfReady: stored=$modelPath loaded=$loadedModelPath engine=${engine != null}")

        if (modelPath.isNotEmpty() && engine != null && modelPath != loadedModelPath) {
            XLog.d(TAG, "loadModelIfReady: model changed ($loadedModelPath -> $modelPath), closing conversation")
            val oldConv = conversation
            engine = null
            conversation = null
            isModelReady = false
            loadedModelPath = null
            executor.submit {
                try {
                    oldConv?.close()
                } catch (e: Exception) {
                    XLog.w(TAG, "loadModelIfReady: conv close error", e)
                }
                postToMain { loadModelIfReady() }
            }
            return
        }

        if (modelPath.isEmpty()) {
            val deviceSupport = LocalModelManager.deviceSupport(activity)
            val defaultModel = deviceSupport.bestSupportedModel
            if (defaultModel == null) {
                sink.modelStatus = "Local model unavailable on this device"
                sink.downloading = false
                setButtonsEnabled(false)
                addSystem(
                    "This device reports ${deviceSupport.deviceRamGb}GB RAM. Current built-in local models need at least ${deviceSupport.minimumBuiltInRamGb}GB."
                )
                return
            }
            sink.modelStatus = "Downloading ${defaultModel.displayName}..."
            sink.downloading = true
            sink.downloadProgress = 0
            setButtonsEnabled(false)

            executor.submit {
                LocalModelManager.downloadModel(activity, defaultModel, object : LocalModelManager.DownloadCallback {
                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                        val pct = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                        postToMain {
                            sink.downloadProgress = pct
                            sink.modelStatus = "Downloading: $pct%"
                        }
                    }

                    override fun onComplete(modelPath: String) {
                        val currentPath = ModelConfigRepository.snapshot().local.modelPath
                        if (currentPath.isEmpty() || currentPath == modelPath) {
                            ModelConfigRepository.activateLocal(modelPath, defaultModel.id)
                        }
                        postToMain {
                            sink.downloading = false
                            loadModelIfReady()
                        }
                    }

                    override fun onError(error: String) {
                        postToMain {
                            sink.downloading = false
                            sink.modelStatus = "Download failed"
                            addSystem("Download failed: $error")
                        }
                    }
                })
            }
            return
        }

        val restoredSystemPrompt = buildRestoredSystemPrompt(conversationId, visibleMessages)
        sink.modelStatus = "Loading..."
        setButtonsEnabled(false)
        val generation = ++localUiGeneration
        executor.submit { loadModel(modelPath, generation, restoredSystemPrompt) }
    }

    fun onResume(
        conversationId: String,
        visibleMessages: List<ChatMessage>,
    ) {
        syncUiToActiveModel()
        val currentModelPath = ModelConfigRepository.snapshot().local.modelPath
        if (currentModelPath.isNotEmpty() && currentModelPath != loadedModelPath) {
            loadModelIfReady(conversationId, visibleMessages)
        } else if (!isModelReady && engine != null && currentModelPath.isNotEmpty()) {
            val generation = ++localUiGeneration
            executor.submit {
                try {
                    try {
                        conversation?.close()
                    } catch (_: Exception) {
                    }
                    conversation = null
                    val lease = LocalModelRuntime.openConversation(
                        activity,
                        currentModelPath,
                        buildConversationConfig(buildRestoredSystemPrompt(conversationId, visibleMessages))
                    )
                    engine = lease.engine
                    conversation = lease.conversation
                    isModelReady = true
                    postToMain {
                        if (!isLocalUiStillExpected(currentModelPath, generation)) {
                            return@postToMain
                        }
                        updateLocalModelStatus(currentModelPath)
                        setButtonsEnabled(true)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to recreate conversation", e)
                    val isSessionConflict = e.message?.contains("session already exists") == true
                    postToMain {
                        if (isSessionConflict) {
                            sink.modelStatus = "⚠ Model busy — tap model to retry"
                            Toast.makeText(
                                activity,
                                "Model is being used by a task. Wait for it to finish, then tap the model name to retry.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            sink.modelStatus = "⚠ Model load failed — tap to retry"
                            Toast.makeText(
                                activity,
                                "Failed to load model: ${e.message?.take(80)}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        setButtonsEnabled(false)
                    }
                }
            }
        } else if (!isModelReady && engine == null && currentModelPath.isNotEmpty()) {
            loadModelIfReady(conversationId, visibleMessages)
        }
    }

    fun onPause(conversationId: String) {
        if (engine != null && ConversationCompactor.needsCompaction(sink.snapshotMessages())) {
            executor.submit {
                try {
                    conversation?.close()
                } catch (_: Exception) {
                }
                conversation = null
                ConversationCompactor.compact(engine!!, sink.snapshotMessages(), activity, conversationId)
                isModelReady = false
            }
        }
        executor.submit {
            try {
                conversation?.close()
            } catch (_: Exception) {
            }
            conversation = null
            isModelReady = false
        }
    }

    fun onDestroy() {
        executor.submit {
            XLog.i(TAG, "onDestroy: closing conversation (engine stays in EngineHolder)")
            try {
                conversation?.close()
            } catch (e: Exception) {
                XLog.w(TAG, "onDestroy: conversation close error", e)
            }
            conversation = null
        }
    }

    fun releaseForTask() {
        try {
            conversation?.close()
        } catch (_: Exception) {
        }
        conversation = null
        isModelReady = false
    }

    fun prepareForTaskStart() {
        try {
            conversation?.close()
        } catch (_: Exception) {
        }
        conversation = null
        isModelReady = false
    }

    fun sendChat(text: String) {
        addUser(text)
        sink.awaitingReply = true
        sink.append(ChatMessage(ChatMessage.Role.ASSISTANT, ChatSink.TYPING_INDICATOR))

        executor.submit {
            try {
                if (cloudClient != null) {
                    ensureCloudHistoryInitialized()
                    cloudHistory.add(UserMessage.from(text))
                    val llmResponse = cloudClient!!.chat(cloudHistory, emptyList())
                    val responseText = llmResponse.text ?: "(no response)"
                    cloudHistory.add(AiMessage.from(responseText))
                    val usage = llmResponse.tokenUsage
                    val inputTokens = usage?.inputTokenCount() ?: (text.length / 4 + 1)
                    val outputTokens = usage?.outputTokenCount() ?: (responseText.length / 4 + 1)
                    val fallbackModelName = cloudModelName ?: ModelConfigRepository.snapshot().activeCloud.modelName
                    val modelTag = llmResponse.modelName ?: fallbackModelName
                    XLog.d(TAG, "sendChat: cloud response modelName='${llmResponse.modelName}', fallback='$fallbackModelName'")
                    postToMain {
                        replaceTypingIndicator(responseText, modelTag)
                        sink.awaitingReply = false
                        sink.sessionTokens += inputTokens + outputTokens
                        sink.sessionCost += ModelPricing.estimateCost(modelTag, inputTokens, outputTokens)
                        onPersistConversation()
                    }
                } else {
                    val currentConversation = conversation
                    if (currentConversation == null || !isModelReady) {
                        throw IllegalStateException("Local model is still loading. Try again in a moment.")
                    }
                    val response = currentConversation.sendMessage(text)
                    val responseText = response?.toString() ?: "(no response)"
                    val inputTokensEst = text.length / 4 + 1
                    val outputTokensEst = responseText.length / 4 + 1
                    val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                    val localModelTag = localModelTag(modelPath)
                    postToMain {
                        replaceTypingIndicator(responseText, localModelTag)
                        sink.awaitingReply = false
                        sink.sessionTokens += inputTokensEst + outputTokensEst
                        onPersistConversation()
                    }
                }
            } catch (e: Exception) {
                if (conversation != null && LocalModelRuntime.isGpuBackendFailure(e)) {
                    XLog.w(TAG, "GPU inference failed, falling back to CPU: ${e.message}")
                    try {
                        val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                        val responseText = retryLocalChatOnCpu(modelPath, text)
                        val inputTokensEst = text.length / 4 + 1
                        val outputTokensEst = responseText.length / 4 + 1
                        val cpuModelTag = localModelTag(modelPath)
                        postToMain {
                            replaceTypingIndicator(responseText, cpuModelTag)
                            sink.awaitingReply = false
                            sink.sessionTokens += inputTokensEst + outputTokensEst
                            updateLocalModelStatus(modelPath)
                            onPersistConversation()
                        }
                        return@submit
                    } catch (cpuError: Exception) {
                        XLog.e(TAG, "CPU fallback also failed", cpuError)
                    }
                }
                XLog.e(TAG, "Chat error", e)
                postToMain {
                    replaceTypingIndicator("Error: ${e.message}")
                    sink.awaitingReply = false
                }
            }
        }
    }

    fun switchModel(modelId: String, displayName: String) {
        if (modelId == "NONE") {
            sink.modelStatus = "No model selected"
            isModelReady = false
            setButtonsEnabled(false)
            XLog.i(TAG, "switchModel: NONE — no model configured for current tab")
            return
        }
        if (modelId == "LOCAL") {
            val localConfig = ModelConfigRepository.snapshot().local
            if (!localConfig.isConfigured) {
                sink.modelStatus = "No model selected"
                isModelReady = false
                setButtonsEnabled(false)
                XLog.i(TAG, "switchModel: LOCAL requested but no local default configured")
                return
            }
            ModelConfigRepository.activateLocal(localConfig.modelPath, localConfig.modelId)
            sink.modelStatus = "● ${localConfig.displayName} · On-device"
            addSystem("Switched to local model")
            loadModelIfReady()
        } else {
            localUiGeneration++
            ModelConfigRepository.activateCloudSelection(modelId)
            suppressNextCloudSwitchMessage = true
            loadModelIfReady()
            addSystem("Switched to $displayName")
        }
        XLog.i(TAG, "Model switched to: $modelId ($displayName)")
    }

    fun startNewConversationRuntime() {
        if (cloudClient != null) {
            cloudHistory.clear()
            cloudHistory.add(SystemMessage.from(BASE_SYSTEM_PROMPT))
            postToMain {
                addSystem("New conversation started.")
                onRefreshSidebarHistory()
            }
            return
        }

        executor.submit {
            try {
                conversation?.close()
            } catch (_: Exception) {
            }
            val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
            if (modelPath.isNotEmpty()) {
                val lease = LocalModelRuntime.openConversation(activity, modelPath, buildConversationConfig())
                engine = lease.engine
                conversation = lease.conversation
                isModelReady = true
            }
            postToMain {
                addSystem("New conversation started.")
                onRefreshSidebarHistory()
            }
        }
    }

    fun restoreConversationRuntime(conversationId: String, messages: List<ChatMessage>) {
        if (cloudClient != null) {
            rebuildCloudHistoryFromVisibleMessages()
            return
        }
        if (engine != null) {
            executor.submit {
                try {
                    try {
                        conversation?.close()
                    } catch (_: Exception) {
                    }
                    val recentMsgs = messages.takeLast(5)
                    val systemPrompt = ConversationCompactor.buildRestoredSystemPrompt(activity, conversationId, recentMsgs)
                    val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                    val lease = LocalModelRuntime.openConversation(
                        context = activity,
                        modelPath = modelPath,
                        conversationConfig = ConversationConfig(
                            systemInstruction = Contents.of(systemPrompt),
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                        )
                    )
                    engine = lease.engine
                    conversation = lease.conversation
                    isModelReady = true
                    postToMain {
                        setButtonsEnabled(true)
                        addSystem("Conversation restored.")
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to restore conversation", e)
                    postToMain { addSystem("History loaded. New context started.") }
                }
            }
        }
    }

    private fun loadModel(
        modelPath: String,
        generation: Long,
        restoredSystemPrompt: String? = null,
    ) {
        try {
            XLog.i(TAG, "loadModel: acquiring shared runtime for $modelPath")
            try {
                conversation?.close()
            } catch (_: Exception) {
            }
            conversation = null
            Thread.sleep(200)

            val lease = LocalModelRuntime.openConversation(
                activity,
                modelPath,
                buildConversationConfig(restoredSystemPrompt)
            )
            engine = lease.engine
            XLog.i(TAG, "loadModel: engine ready (${lease.backendLabel})")
            conversation = lease.conversation

            isModelReady = true
            loadedModelPath = modelPath
            postToMain {
                if (!isLocalUiStillExpected(modelPath, generation)) {
                    XLog.i(TAG, "Ignoring stale local UI update for $modelPath (generation=$generation)")
                    return@postToMain
                }
                updateLocalModelStatus(modelPath)
                setButtonsEnabled(true)
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Model load failed", e)
            val isSessionConflict = e.message?.contains("session already exists") == true
                || e.message?.contains("5 retries") == true
            postToMain {
                if (isSessionConflict) {
                    sink.modelStatus = "⚠ Model busy — tap model to retry"
                    addSystem("Model is being used by a background task. Wait for it to finish, then tap the model name above to reload.")
                    Toast.makeText(
                        activity,
                        "Model is busy. Wait for the task to finish, then tap the model name to retry.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    sink.modelStatus = "⚠ Load failed — tap model to retry"
                    addSystem("Failed to load model: ${e.message?.take(100)}")
                    Toast.makeText(
                        activity,
                        "Model load failed: ${e.message?.take(80)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                setButtonsEnabled(false)
            }
        }
    }

    private fun retryLocalChatOnCpu(modelPath: String, text: String): String {
        require(modelPath.isNotEmpty()) { "Local model path missing for CPU retry" }
        try {
            conversation?.close()
        } catch (_: Exception) {
        }
        conversation = null
        LocalModelRuntime.forceCpuEngine(activity, modelPath)
        val lease = LocalModelRuntime.openConversation(
            context = activity,
            modelPath = modelPath,
            conversationConfig = buildConversationConfig(),
            preferCpu = true,
        )
        engine = lease.engine
        loadedModelPath = modelPath
        conversation = lease.conversation
        XLog.i(TAG, "retryLocalChatOnCpu: CPU runtime ready, retrying sendMessage")
        return conversation!!.sendMessage(text)?.toString() ?: "(no response)"
    }

    private fun buildConversationConfig(systemPrompt: String? = null): ConversationConfig {
        val finalPrompt = io.agents.pokeclaw.agent.PromptUtils
            .applyGlobalPrompt(systemPrompt ?: BASE_SYSTEM_PROMPT)
        return ConversationConfig(
            systemInstruction = Contents.of(finalPrompt),
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
        )
    }

    private fun buildRestoredSystemPrompt(
        conversationId: String?,
        visibleMessages: List<ChatMessage>,
    ): String? {
        val meaningfulMessages = visibleMessages.filter {
            it.role == ChatMessage.Role.USER || it.role == ChatMessage.Role.ASSISTANT
        }
        if (conversationId.isNullOrBlank() || meaningfulMessages.isEmpty()) return null
        return ConversationCompactor.buildRestoredSystemPrompt(
            activity,
            conversationId,
            meaningfulMessages.takeLast(6)
        )
    }

    private fun rebuildCloudHistoryFromVisibleMessages() {
        cloudHistory.clear()
        cloudHistory.add(SystemMessage.from(BASE_SYSTEM_PROMPT))
        sink.snapshotMessages().forEach { msg ->
            when (msg.role) {
                ChatMessage.Role.USER -> cloudHistory.add(UserMessage.from(msg.content))
                ChatMessage.Role.ASSISTANT -> cloudHistory.add(AiMessage.from(msg.content))
                else -> Unit
            }
        }
    }

    private fun ensureCloudHistoryInitialized() {
        if (cloudHistory.isEmpty()) {
            rebuildCloudHistoryFromVisibleMessages()
        }
    }

    private fun replaceTypingIndicator(text: String, actualModelName: String? = null) {
        val modelTag = actualModelName
            ?: sink.modelStatus.removePrefix("● ").split(" ·").firstOrNull()?.trim()
            ?: ""
        sink.replaceTypingIndicator(text, modelTag)
    }

    private fun addUser(text: String) {
        sink.append(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        val last = sink.lastMessage()
        if (last?.role == ChatMessage.Role.SYSTEM && last.content.equals(text, ignoreCase = true)) {
            return
        }
        sink.append(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun updateLocalModelStatus(modelPath: String?) {
        if (modelPath.isNullOrEmpty()) {
            sink.modelStatus = "No model selected"
            return
        }
        val modelInfo = LocalModelManager.AVAILABLE_MODELS.find { modelPath.endsWith(it.fileName) }
        val modelName = modelInfo?.displayName ?: modelPath.substringAfterLast('/').substringBeforeLast('.')
        val backendLabel = LocalModelRuntime.currentBackendLabel(modelPath) ?: "On-device"
        sink.modelStatus = "● $modelName · $backendLabel"
    }

    fun syncUiToActiveModel() {
        val config = ModelConfigRepository.snapshot()
        if (config.isLocalActive()) {
            val modelPath = config.local.modelPath
            if (modelPath.isNullOrBlank()) {
                sink.modelStatus = "No model selected"
                setButtonsEnabled(false)
                return
            }
            if (loadedModelPath == modelPath && isModelReady && cloudClient == null) {
                updateLocalModelStatus(modelPath)
                setButtonsEnabled(true)
                return
            }
            loadModelIfReady()
            return
        }

        val cloud = config.activeCloud
        if (!cloud.isConfigured) {
            sink.modelStatus = "No model selected"
            setButtonsEnabled(false)
            return
        }
        if (conversation != null) {
            try {
                conversation?.close()
            } catch (_: Exception) {
            }
            conversation = null
            isModelReady = false
        }
        loadedModelPath = null
        if (cloudClient == null || cloudModelName != cloud.modelName || !isModelReady) {
            loadModelIfReady()
            return
        }
        sink.modelStatus = "● ${cloud.modelName} · Cloud"
        setButtonsEnabled(true)
    }

    private fun isLocalUiStillExpected(modelPath: String, generation: Long): Boolean {
        val config = ModelConfigRepository.snapshot()
        return generation == localUiGeneration &&
            config.isLocalActive() &&
            config.local.modelPath == modelPath
    }

    private fun localModelTag(modelPath: String): String {
        val baseName = modelPath.takeIf { it.isNotEmpty() }?.let { File(it).nameWithoutExtension } ?: "Local"
        val backendLabel = LocalModelRuntime.currentBackendLabel(modelPath)
        return if (backendLabel.isNullOrBlank() || backendLabel.equals("GPU", ignoreCase = true)) {
            baseName
        } else {
            "$baseName ($backendLabel)"
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        sink.inputEnabled = enabled
    }

    private fun postToMain(action: () -> Unit) {
        activity.runOnUiThread(action)
    }
}
