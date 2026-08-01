package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.db.*
import com.example.data.network.GeminiApiClient
import com.example.data.network.SgaApiClient
import com.example.data.repository.MoodleRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MoodleViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MoodleViewModel"

    private companion object {
        // Renew the SGA access token when it expires within this window
        const val SGA_REFRESH_LEEWAY_MILLIS = 5 * 60 * 1000L
    }

    private val database = AppDatabase.getDatabase(application)
    private val repository = MoodleRepository(database.moodleDao())

    private val sharedPrefs = application.getSharedPreferences("moodle_ai_prefs", Context.MODE_PRIVATE)

    // --- State Variables ---
    val allAccounts: StateFlow<List<MoodleAccount>> = repository.allAccounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAccount: StateFlow<MoodleAccount?> = repository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Courses for All Accounts
    val courses: StateFlow<List<Course>> = repository.allCourses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Activities for All Accounts
    val activities: StateFlow<List<ActivityItem>> = repository.allActivities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Notification rules for All Accounts
    val notifications: StateFlow<List<NotificationRule>> = repository.allNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Config: Dark Theme & Gemini API Key
    private val _isDarkTheme = MutableStateFlow(sharedPrefs.getBoolean("dark_theme", true))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _geminiApiKey = MutableStateFlow(sharedPrefs.getString("gemini_api_key", BuildConfig.GEMINI_API_KEY) ?: "")
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    // Config: OpenCode AI
    private val _useOpenCode = MutableStateFlow(sharedPrefs.getBoolean("use_opencode", false))
    val useOpenCode: StateFlow<Boolean> = _useOpenCode.asStateFlow()

    private val _openCodeApiKey = MutableStateFlow(sharedPrefs.getString("opencode_api_key", "") ?: "")
    val openCodeApiKey: StateFlow<String> = _openCodeApiKey.asStateFlow()

    private val _openCodeModel = MutableStateFlow(
        sharedPrefs.getString("opencode_model", "big-pickle")?.let {
            if (it == "FREE: Big Pickle") "big-pickle"
            else if (it == "nemutron 3 ultra free" || it == "nemotron 3 ultra free") "nemotron-3-ultra-free"
            else it
        } ?: "big-pickle"
    )
    val openCodeModel: StateFlow<String> = _openCodeModel.asStateFlow()

    private val _deactivateThinking = MutableStateFlow(sharedPrefs.getBoolean("deactivate_thinking", true))
    val deactivateThinking: StateFlow<Boolean> = _deactivateThinking.asStateFlow()

    // Config: SGA Estudiante JWT Auth
    private val _sgaAccessToken = MutableStateFlow(sharedPrefs.getString("sga_access_token", "") ?: "")
    val sgaAccessToken: StateFlow<String> = _sgaAccessToken.asStateFlow()

    private val _sgaRefreshToken = MutableStateFlow(sharedPrefs.getString("sga_refresh_token", "") ?: "")
    val sgaRefreshToken: StateFlow<String> = _sgaRefreshToken.asStateFlow()

    private val _sgaSessionPayload = MutableStateFlow(sharedPrefs.getString("sga_session_payload", "") ?: "")
    val sgaSessionPayload: StateFlow<String> = _sgaSessionPayload.asStateFlow()

    private val _sgaUser = MutableStateFlow(sharedPrefs.getString("sga_user", "") ?: "")
    val sgaUser: StateFlow<String> = _sgaUser.asStateFlow()

    private val _sgaPass = MutableStateFlow(sharedPrefs.getString("sga_pass", "") ?: "")
    val sgaPass: StateFlow<String> = _sgaPass.asStateFlow()

    // SGA session validity, derived from the access token's JWT exp claim
    private val _sgaSessionValid = MutableStateFlow(false)
    val sgaSessionValid: StateFlow<Boolean> = _sgaSessionValid.asStateFlow()

    private val _sgaExpiresAtMillis = MutableStateFlow(0L)
    val sgaExpiresAtMillis: StateFlow<Long> = _sgaExpiresAtMillis.asStateFlow()

    // Sync State
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // Chat Conversation State
    val chatMessages = mutableStateListOf<ChatMessage>()
    
    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading: StateFlow<Boolean> = _isChatLoading.asStateFlow()

    private val _chatProgressStatus = MutableStateFlow<String?>("Moodle AI is thinking...")
    val chatProgressStatus: StateFlow<String?> = _chatProgressStatus.asStateFlow()

    // Ringing Alarm State (Loud system-wide alarm support with manual dismissal UI)
    private var activeRingtone: android.media.Ringtone? = null

    private val _isAlarmRinging = MutableStateFlow(false)
    val isAlarmRinging: StateFlow<Boolean> = _isAlarmRinging.asStateFlow()

    private val _ringingAlarmTitle = MutableStateFlow("")
    val ringingAlarmTitle: StateFlow<String> = _ringingAlarmTitle.asStateFlow()

    private val _ringingAlarmBody = MutableStateFlow("")
    val ringingAlarmBody: StateFlow<String> = _ringingAlarmBody.asStateFlow()

    fun dismissAlarm() {
        _isAlarmRinging.value = false
        activeRingtone?.stop()
        activeRingtone = null
    }

    private fun playAlarmSound() {
        try {
            val alarmUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            val ringtone = android.media.RingtoneManager.getRingtone(getApplication(), alarmUri)
            activeRingtone?.stop()
            activeRingtone = ringtone
            ringtone.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm sound", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeRingtone?.stop()
    }

    // Active Activity for Detail View or dialogs
    private val _selectedCourseId = MutableStateFlow<Int?>(null)
    val selectedCourseId: StateFlow<Int?> = _selectedCourseId.asStateFlow()

    init {
        // Load persisted chat history or show welcome message
        loadChatHistory()
        
        // Start background checks for notifications/alarms
        startAlarmChecker()

        // Proactively renew the SGA session so the AI never hits an expired token
        viewModelScope.launch { refreshSgaIfNeeded() }
    }

    // --- Actions ---

    fun selectCourse(courseId: Int?) {
        _selectedCourseId.value = courseId
    }

    fun setDarkTheme(enabled: Boolean) {
        _isDarkTheme.value = enabled
        sharedPrefs.edit().putBoolean("dark_theme", enabled).apply()
    }

    fun setGeminiApiKey(key: String) {
        _geminiApiKey.value = key
        sharedPrefs.edit().putString("gemini_api_key", key).apply()
    }

    fun setUseOpenCode(enabled: Boolean) {
        _useOpenCode.value = enabled
        sharedPrefs.edit().putBoolean("use_opencode", enabled).apply()
    }

    fun setOpenCodeApiKey(key: String) {
        _openCodeApiKey.value = key
        sharedPrefs.edit().putString("opencode_api_key", key).apply()
    }

    fun setOpenCodeModel(model: String) {
        _openCodeModel.value = model
        sharedPrefs.edit().putString("opencode_model", model).apply()
    }

    fun setDeactivateThinking(enabled: Boolean) {
        _deactivateThinking.value = enabled
        sharedPrefs.edit().putBoolean("deactivate_thinking", enabled).apply()
    }

    /**
     * Authenticates with SGA Estudiante and stores the JWT session tokens and user profile payload.
     */
    fun loginSga(user: String, pass: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                val result = com.example.data.network.SgaApiClient.login(user, pass)
                _sgaAccessToken.value = result.accessToken
                _sgaRefreshToken.value = result.refreshToken
                _sgaUser.value = user
                _sgaPass.value = pass
                val payloadStr = result.decodedPayload?.toString() ?: ""
                _sgaSessionPayload.value = payloadStr

                sharedPrefs.edit()
                    .putString("sga_access_token", result.accessToken)
                    .putString("sga_refresh_token", result.refreshToken)
                    .putString("sga_session_payload", payloadStr)
                    .putString("sga_user", user)
                    .putString("sga_pass", pass)
                    .apply()

                updateSgaSessionMeta()

                _isSyncing.value = false
                val personaName = result.decodedPayload?.optJSONObject("persona")?.optString("nombres") ?: user
                toastOnMain("Logged in to UNEMI SGA! Welcome, $personaName")
                onSuccess()
            } catch (e: Exception) {
                _isSyncing.value = false
                Log.e(TAG, "Error logging in to SGA", e)
                onError(e.message ?: "SGA Authentication failed")
            }
        }
    }

    fun updateSgaTokens(newAccess: String, newRefresh: String) {
        _sgaAccessToken.value = newAccess
        _sgaRefreshToken.value = newRefresh

        // A rotated token (career/period switch, refresh) carries the new session
        // profile in its JWT payload — keep the stored payload in sync so the AI
        // always sees the current career/period ids.
        val newPayload = SgaApiClient.decodeJwtPayload(newAccess)?.toString()
        if (newPayload != null && newPayload.isNotBlank()) {
            _sgaSessionPayload.value = newPayload
            sharedPrefs.edit()
                .putString("sga_session_payload", newPayload)
                .apply()
        }

        sharedPrefs.edit()
            .putString("sga_access_token", newAccess)
            .putString("sga_refresh_token", newRefresh)
            .apply()
        updateSgaSessionMeta()
    }

    /**
     * Recomputes SGA session validity from the stored access token's exp claim.
     */
    private fun updateSgaSessionMeta() {
        val token = _sgaAccessToken.value
        if (token.isEmpty()) {
            _sgaSessionValid.value = false
            _sgaExpiresAtMillis.value = 0L
            return
        }
        val exp = SgaApiClient.jwtExpiryMillis(token)
        _sgaExpiresAtMillis.value = exp ?: 0L
        // Tokens without a decodable exp claim are assumed valid
        _sgaSessionValid.value = exp == null || exp > System.currentTimeMillis()
    }

    /**
     * Ensures the stored SGA access token is valid, silently renewing it via the
     * refresh token (or stored credentials as a fallback) when it is expired or
     * about to expire. Returns true if a valid session is available afterwards.
     */
    suspend fun refreshSgaIfNeeded(force: Boolean = false): Boolean {
        val token = _sgaAccessToken.value

        if (token.isNotEmpty()) {
            val exp = SgaApiClient.jwtExpiryMillis(token)
            if (!force && exp != null && exp - System.currentTimeMillis() > SGA_REFRESH_LEEWAY_MILLIS) {
                _sgaSessionValid.value = true
                _sgaExpiresAtMillis.value = exp
                return true
            }

            // Step 1: refresh token
            val curRefresh = _sgaRefreshToken.value
            if (curRefresh.isNotEmpty()) {
                try {
                    Log.i(TAG, "Proactively refreshing SGA session...")
                    val result = SgaApiClient.refreshToken(curRefresh)
                    updateSgaTokens(result.accessToken, result.refreshToken)
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "SGA proactive refresh failed: ${e.message}")
                }
            }
        }

        // Step 2: silent re-login with stored credentials (also covers the case
        // where no access token is stored at all but credentials exist).
        val curUser = _sgaUser.value
        val curPass = _sgaPass.value
        if (curUser.isNotEmpty() && curPass.isNotEmpty()) {
            try {
                Log.i(TAG, "Attempting silent SGA re-login for '$curUser'...")
                val result = SgaApiClient.login(curUser, curPass)
                _sgaSessionPayload.value = result.decodedPayload?.toString() ?: _sgaSessionPayload.value
                updateSgaTokens(result.accessToken, result.refreshToken)
                sharedPrefs.edit()
                    .putString("sga_session_payload", _sgaSessionPayload.value)
                    .apply()
                return true
            } catch (e: Exception) {
                Log.e(TAG, "SGA proactive re-login failed: ${e.message}")
            }
        }

        _sgaSessionValid.value = false
        if (token.isEmpty()) {
            _sgaExpiresAtMillis.value = 0L
        }
        return false
    }

    /**
     * Forces an immediate SGA session renewal (used by the UI Refresh button).
     * Shows a toast with the outcome; when no stored credentials exist, the
     * caller is told to open the credential dialog so the user can reconnect.
     */
    fun refreshSgaNow(onNeedsCredentials: () -> Unit = {}) {
        viewModelScope.launch {
            val ok = refreshSgaIfNeeded(force = true)
            if (ok) {
                toastOnMain("SGA session renewed successfully")
            } else {
                val hasCreds = _sgaUser.value.isNotEmpty() && _sgaPass.value.isNotEmpty()
                if (hasCreds) {
                    toastOnMain("SGA reconnection failed — please check your credentials")
                } else {
                    toastOnMain("SGA session expired — please re-enter your credentials to reconnect")
                    onNeedsCredentials()
                }
            }
        }
    }

    fun clearSgaSession() {
        _sgaAccessToken.value = ""
        _sgaRefreshToken.value = ""
        _sgaSessionPayload.value = ""
        _sgaUser.value = ""
        _sgaPass.value = ""
        _sgaSessionValid.value = false
        _sgaExpiresAtMillis.value = 0L
        sharedPrefs.edit()
            .remove("sga_access_token")
            .remove("sga_refresh_token")
            .remove("sga_session_payload")
            .remove("sga_user")
            .remove("sga_pass")
            .apply()
        toastOnMain("UNEMI SGA Session disconnected")
    }

    /**
     * Registers a new account and performs an initial sync.
     */
    fun addMoodleAccount(url: String, username: String, pass: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                val account = repository.addNewAccount(url, username, pass)
                _isSyncing.value = false
                toastOnMain("Logged in successfully! Welcome, ${account.fullName}")
                onSuccess()
            } catch (e: Exception) {
                _isSyncing.value = false
                Log.e(TAG, "Error adding account", e)
                onError(e.message ?: "Authentication failed")
            }
        }
    }

    fun switchAccount(accountId: Int) {
        viewModelScope.launch {
            repository.switchAccount(accountId)
            toastOnMain("Switched account")
        }
    }

    fun deleteAccount(accountId: Int) {
        viewModelScope.launch {
            repository.deleteAccount(accountId)
            toastOnMain("Account removed")
        }
    }

    fun syncMoodle() {
        val accounts = allAccounts.value
        if (accounts.isEmpty()) return
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                repository.syncAllAccounts(accounts)
                toastOnMain("All accounts synchronized successfully!")
            } catch (e: Exception) {
                Log.e(TAG, "Manual sync failed", e)
                toastOnMain("Sync failed: ${e.message}")
            } finally {
                _isSyncing.value = false
            }
        }
    }

     /**
     * Chat with the Gemini model.
     */
    fun sendMessage(query: String) {
        if (query.trim().isEmpty()) return
        
        chatMessages.add(ChatMessage(sender = "user", text = query, timestamp = System.currentTimeMillis()))
        saveChatHistory()
        
        viewModelScope.launch {
            _isChatLoading.value = true
            _chatProgressStatus.value = "Thinking..."

            var streamedReply = false
            
            val activeAcc = activeAccount.value
            val activeAccId = activeAcc?.id ?: 0
            val curCourses = courses.value
            val curActivities = activities.value
            val curAlarms = notifications.value

            // Ensure the SGA session is fresh before the AI queries university endpoints
            if (sgaAccessToken.value.isNotEmpty()) {
                refreshSgaIfNeeded()
            }
 
            val chatResult = GeminiApiClient.chatWithAi(
                userQuery = query,
                apiKey = geminiApiKey.value,
                courses = curCourses,
                activities = curActivities,
                rules = curAlarms,
                allAccounts = allAccounts.value,
                useOpenCode = useOpenCode.value,
                openCodeApiKey = openCodeApiKey.value,
                openCodeModel = openCodeModel.value,
                deactivateThinking = deactivateThinking.value,
                sgaAccessToken = sgaAccessToken.value,
                sgaRefreshToken = sgaRefreshToken.value,
                sgaSessionPayload = sgaSessionPayload.value,
                sgaUser = sgaUser.value,
                sgaPass = sgaPass.value,
                sgaSessionValid = sgaSessionValid.value,
                chatHistory = chatMessages.dropLast(1),
                onProgress = { status ->
                    _chatProgressStatus.value = status
                },
                onPartialReply = { partialText ->
                    // Streamed text arrives from a background thread — hop to main before
                    // touching Compose state.
                    Handler(Looper.getMainLooper()).post {
                        streamedReply = true
                        if (chatMessages.isNotEmpty() && chatMessages.last().sender == "ai") {
                            val idx = chatMessages.size - 1
                            chatMessages[idx] = chatMessages[idx].copy(text = partialText)
                        } else {
                            chatMessages.add(
                                ChatMessage(
                                    sender = "ai",
                                    text = partialText,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                },
                onSgaTokenRefreshed = { newAccess, newRefresh ->
                    updateSgaTokens(newAccess, newRefresh)
                }
            )
 
            // Streamed text updates are posted to the main looper from background
            // threads. Wait for any still-queued posts to be processed before
            // finalizing the message, so streamedReply reflects every chunk.
            suspendCancellableCoroutine<Unit> { cont ->
                Handler(Looper.getMainLooper()).post {
                    cont.resume(Unit)
                }
            }
 
            _isChatLoading.value = false
            
            var actionStatus: String? = null
            // Execute actions returned by the AI
            chatResult.action?.let { action ->
                when (action) {
                    is GeminiApiClient.AiAction.ScheduleNotification -> {
                        if (activeAccId == 0) {
                            actionStatus = "⚠️ Could not schedule alarm (no active Moodle account logged in)."
                        } else {
                            val scheduledTime = if (action.triggerCode.isNotEmpty()) System.currentTimeMillis() else action.timeScheduled
                            val rule = NotificationRule(
                                accountId = activeAccId,
                                title = action.title,
                                body = action.body,
                                triggerType = "CUSTOM_CODE",
                                triggerCode = action.triggerCode,
                                timeScheduled = scheduledTime,
                                ruleType = action.ruleType
                            )
                            repository.createNotification(rule)
                            val prefix = if (action.ruleType == "ALARM") "🚨 [ALARM]" else "🔔 [NOTIFICATION]"
                            actionStatus = "$prefix Active rule created: '$action.title'"
                        }
                    }
                    is GeminiApiClient.AiAction.DeleteNotification -> {
                        repository.deleteNotification(action.id)
                        database.moodleDao().deleteTriggeredAlarmsForRule(action.id)
                        actionStatus = "🗑️ Cancelled and deleted rule with ID ${action.id}."
                    }
                    is GeminiApiClient.AiAction.EditNotification -> {
                        val existingRule = notifications.value.find { it.id == action.id }
                        if (existingRule == null && activeAccId == 0) {
                            actionStatus = "⚠️ Could not edit alarm: rule ID ${action.id} not found and no active Moodle account logged in."
                        } else {
                            val accId = existingRule?.accountId ?: activeAccId
                            val finalTitle = if (action.title.isNotEmpty()) action.title else (existingRule?.title ?: "Alarm")
                            val finalBody = if (action.body.isNotEmpty()) action.body else (existingRule?.body ?: "")
                            val finalCode = if (action.triggerCode.isNotEmpty()) action.triggerCode else (existingRule?.triggerCode ?: "")
                            val finalTime = if (action.timeScheduled != 0L) action.timeScheduled else (existingRule?.timeScheduled ?: System.currentTimeMillis())
                            val finalRuleType = if (action.ruleType.isNotEmpty()) action.ruleType else (existingRule?.ruleType ?: "NOTIFICATION")

                            val rule = NotificationRule(
                                id = action.id,
                                accountId = accId,
                                title = finalTitle,
                                body = finalBody,
                                triggerType = "CUSTOM_CODE",
                                triggerCode = finalCode,
                                timeScheduled = finalTime,
                                ruleType = finalRuleType
                            )
                            repository.createNotification(rule)
                            val prefix = if (finalRuleType == "ALARM") "🚨 [ALARM]" else "🔔 [NOTIFICATION]"
                            actionStatus = "✏️ Updated rule '$finalTitle' (ID ${action.id}) scheduled for: *${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm (z)", java.util.Locale.getDefault()).format(java.util.Date(finalTime))}*"
                        }
                    }
                }
            }
 
            val replyText = chatResult.reply
            if (streamedReply) {
                // The final answer already streamed into the UI as it was generated;
                // just set the authoritative full text and save.
                if (chatMessages.isNotEmpty() && chatMessages.last().sender == "ai") {
                    val idx = chatMessages.size - 1
                    chatMessages[idx] = chatMessages[idx].copy(text = replyText, actionApplied = actionStatus)
                } else {
                    chatMessages.add(
                        ChatMessage(
                            sender = "ai",
                            text = replyText,
                            timestamp = System.currentTimeMillis(),
                            actionApplied = actionStatus
                        )
                    )
                }
                saveChatHistory()
            } else {
                val words = replyText.split(" ")
                if (words.isEmpty() || replyText.trim().isEmpty()) {
                    chatMessages.add(
                        ChatMessage(
                            sender = "ai",
                            text = replyText,
                            timestamp = System.currentTimeMillis(),
                            actionApplied = actionStatus
                        )
                    )
                    saveChatHistory()
                } else {
                    val streamingMsg = ChatMessage(
                        sender = "ai",
                        text = "",
                        timestamp = System.currentTimeMillis(),
                        actionApplied = actionStatus
                    )
                    chatMessages.add(streamingMsg)
                    val targetIndex = chatMessages.size - 1
     
                    var currentText = ""
                    for (i in words.indices) {
                        currentText += (if (i == 0) "" else " ") + words[i]
                        if (targetIndex in chatMessages.indices) {
                            chatMessages[targetIndex] = chatMessages[targetIndex].copy(text = currentText)
                        }
                        delay(15)
                    }
                    saveChatHistory()
                }
            }
        }
    }

    fun clearChatHistory() {
        chatMessages.clear()
        sharedPrefs.edit().remove("saved_chat_history").apply()
        loadChatHistory()
    }

    private fun saveChatHistory() {
        try {
            val last15 = chatMessages.takeLast(15)
            val jsonArray = org.json.JSONArray()
            for (msg in last15) {
                val obj = org.json.JSONObject()
                obj.put("sender", msg.sender)
                obj.put("text", msg.text)
                obj.put("timestamp", msg.timestamp)
                if (msg.actionApplied != null) {
                    obj.put("actionApplied", msg.actionApplied)
                }
                jsonArray.put(obj)
            }
            sharedPrefs.edit().putString("saved_chat_history", jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving chat history", e)
        }
    }

    private fun loadChatHistory() {
        val jsonStr = sharedPrefs.getString("saved_chat_history", null)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val jsonArray = org.json.JSONArray(jsonStr)
                val loaded = mutableListOf<ChatMessage>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val sender = obj.optString("sender", "ai")
                    val text = obj.optString("text", "")
                    val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    val actionApplied = if (obj.has("actionApplied") && !obj.isNull("actionApplied")) obj.optString("actionApplied") else null
                    loaded.add(ChatMessage(sender, text, timestamp, actionApplied))
                }
                if (loaded.isNotEmpty()) {
                    chatMessages.clear()
                    chatMessages.addAll(loaded.takeLast(15))
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading chat history", e)
            }
        }

        // Welcome message
        chatMessages.clear()
        chatMessages.add(
            ChatMessage(
                sender = "ai",
                text = "Welcome to your **Moodle AI Companion**! 🎓🤖\n\nI am your dedicated academic assistant. Connect your Moodle accounts in the **Configurations** or click **Use Demo Mode** to load mock university subjects and deadlines.\n\nAsk me anything! For example:\n* *\"What are my closing assignments this week?\"*\n* *\"Am I passing my Artificial Intelligence course? Summarize my grades.\"*\n* *\"Set a deadline alarm for Quiz 1.\"*",
                timestamp = System.currentTimeMillis()
            )
        )
    }

    fun deleteNotificationDirectly(id: Int) {
        viewModelScope.launch {
            repository.deleteNotification(id)
            database.moodleDao().deleteTriggeredAlarmsForRule(id)
            toastOnMain("Notification cancelled")
        }
    }

    // --- Helper UI Alarms Trigger ---
    private fun startAlarmChecker() {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                viewModelScope.launch(Dispatchers.IO) {
                    val alarms = database.moodleDao().getAllNotificationsFlow().firstOrNull() ?: emptyList()
                    val activeAccount = database.moodleDao().getActiveAccount()
                    val now = System.currentTimeMillis()

                    if (activeAccount != null) {
                        val coursesList = database.moodleDao().getCoursesForAccount(activeAccount.id)
                        val activitiesList = database.moodleDao().getActivitiesForAccount(activeAccount.id)

                        val mappedCourses = coursesList.map { course ->
                            mapOf(
                                "dbId" to course.dbId,
                                "moodleCourseId" to course.moodleCourseId,
                                "fullName" to course.fullName,
                                "shortName" to course.shortName
                            )
                        }
                        val mappedActivities = activitiesList.map { activity ->
                            mapOf(
                                "dbId" to activity.dbId,
                                "courseId" to activity.courseId,
                                "moodleActivityId" to activity.moodleActivityId,
                                "name" to activity.name,
                                "moduleType" to activity.moduleType,
                                "dueDate" to activity.dueDate?.let { it * 1000L },
                                "status" to activity.status,
                                "grade" to (activity.grade?.toDoubleOrNull() ?: 0.0),
                                "maxGrade" to (activity.maxGrade?.toDoubleOrNull() ?: 100.0)
                            )
                        }

                        alarms.forEach { alarm ->
                            if (alarm.isEnabled) {
                                if (alarm.triggerType == "CUSTOM_CODE") {
                                    if (alarm.isActive) {
                                        val interpreter = com.example.data.rules.ScriptInterpreter(mappedCourses, mappedActivities) { title, body, triggerKey, isAlarm ->
                                            viewModelScope.launch(Dispatchers.IO) {
                                                val fullKey = "rule_${alarm.id}_$triggerKey"
                                                val alreadyFired = database.moodleDao().getTriggeredAlarmsForRule(alarm.id).any { it.triggerKey == fullKey }
                                                if (!alreadyFired) {
                                                    database.moodleDao().insertTriggeredAlarm(
                                                        com.example.data.db.TriggeredAlarm(alarm.id, fullKey, System.currentTimeMillis())
                                                    )
                                                    withContext(Dispatchers.Main) {
                                                        triggerLocalNotification(title, body, isAlarm || alarm.ruleType == "ALARM")
                                                    }
                                                    // Deactivate rule once triggered
                                                    database.moodleDao().insertNotification(alarm.copy(isActive = false))
                                                }
                                            }
                                        }
                                        interpreter.execute(alarm.triggerCode)
                                    }
                                } else {
                                    if (alarm.isActive && alarm.timeScheduled in (now - 5000)..now) {
                                        withContext(Dispatchers.Main) {
                                            triggerLocalNotification(alarm.title, alarm.body, alarm.ruleType == "ALARM")
                                        }
                                        database.moodleDao().insertNotification(alarm.copy(isActive = false))
                                    }
                                }
                            }
                        }
                    } else {
                        alarms.forEach { alarm ->
                            if (alarm.isEnabled && alarm.isActive && alarm.triggerType != "CUSTOM_CODE" && alarm.timeScheduled in (now - 5000)..now) {
                                withContext(Dispatchers.Main) {
                                    triggerLocalNotification(alarm.title, alarm.body, alarm.ruleType == "ALARM")
                                }
                                database.moodleDao().insertNotification(alarm.copy(isActive = false))
                            }
                        }
                    }
                }
                mainHandler.postDelayed(this, 5000)
            }
        }, 5000)
    }

    private fun triggerLocalNotification(title: String, body: String, isAlarm: Boolean) {
        val context = getApplication<Application>()
        try {
            if (isAlarm) {
                playAlarmSound()
                _ringingAlarmTitle.value = title
                _ringingAlarmBody.value = body
                _isAlarmRinging.value = true
            }

            // Standard notification trigger
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = if (isAlarm) "moodle_alarms_loud" else "moodle_notifications"
            val channelName = if (isAlarm) "Moodle Alarms (Loud)" else "Moodle Notifications"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val importance = android.app.NotificationManager.IMPORTANCE_HIGH
                val channel = android.app.NotificationChannel(channelId, channelName, importance)
                if (isAlarm) {
                    val audioAttributes = android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .build()
                    val alarmUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                    channel.setSound(alarmUri, audioAttributes)
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(if (isAlarm) android.R.drawable.ic_lock_idle_alarm else android.R.drawable.ic_popup_reminder)
                .setContentTitle(if (isAlarm) "🚨 $title" else "🔔 $title")
                .setContentText(body)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
            
            // Show local toast
            Handler(Looper.getMainLooper()).post {
                val prefix = if (isAlarm) "🚨 [ALARM]" else "🔔 [NOTIFICATION]"
                Toast.makeText(context, "$prefix: $title - $body", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display notification", e)
        }
    }

    private fun toastOnMain(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
        }
    }
}

data class ChatMessage(
    val sender: String, // "user" or "ai"
    val text: String,
    val timestamp: Long,
    val actionApplied: String? = null
)
