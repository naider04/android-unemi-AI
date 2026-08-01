package com.example.data.network

import android.util.Log
import com.example.data.db.ActivityItem
import com.example.data.db.Course
import com.example.data.db.MoodleAccount

import com.example.data.db.NotificationRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object GeminiApiClient {
    private const val TAG = "GeminiApiClient"
    private const val MODEL_NAME = "gemini-3.5-flash"

    // Keep per-message history bounded so the prompt stays small and fast to process
    private const val MAX_HISTORY_CHARS_PER_MSG = 1200

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // SGA refresh tokens are single-use (rotated on every refresh). Parallel tool
    // calls can 401 simultaneously and would each try to refresh with the same
    // token, invalidating each other. This mutex makes exactly one coroutine do
    // the refresh and everyone else wait for it, then retry with the fresh token.
    private val sgaRefreshMutex = Mutex()

    private val userIdCache = ConcurrentHashMap<String, Int>()

    private suspend fun getCachedUserId(url: String, token: String): Int {
        val key = "$url:$token"
        userIdCache[key]?.let { return it }
        return try {
            withTimeoutOrNull(2500L) {
                val siteInfo = MoodleApiClient.fetchSiteInfo(url, token)
                if (siteInfo.userId != 0) {
                    userIdCache[key] = siteInfo.userId
                }
                siteInfo.userId
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun executeOpenCodeRequest(
        request: Request,
        onProgress: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            try {
                if (attempt > 1) {
                    onProgress?.invoke("Retrying OpenCode connection (attempt $attempt/$maxAttempts)...")
                    delay(2000L * attempt)
                }
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string() ?: ""
                        Log.e(TAG, "OpenCode API error (attempt $attempt): ${response.code} - $errBody")
                        if (response.code in listOf(429, 500, 502, 503, 504) && attempt < maxAttempts) {
                            lastException = IOException("Server status ${response.code}: $errBody")
                            return@use
                        }
                        throw IOException("Server responded with code ${response.code}: $errBody")
                    }
                    return@withContext response.body?.string() ?: throw IOException("Empty response from OpenCode")
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "OpenCode request attempt $attempt failed: ${e.message}")
            }
        }
        throw lastException ?: IOException("Failed to connect to OpenCode after $maxAttempts attempts")
    }

    /**
     * Executes an OpenCode request with SSE streaming and assembles the assistant
     * message (text and/or tool_calls) from the deltas.
     * @param onPartialContent Invoked with the full text accumulated so far for the
     *        current turn, as each chunk arrives. Only called while the turn has no
     *        tool calls yet, so the user only ever sees the final answer text.
     */
    private suspend fun executeOpenCodeStream(
        request: Request,
        onProgress: ((String) -> Unit)? = null,
        onPartialContent: ((String) -> Unit)? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            try {
                if (attempt > 1) {
                    onProgress?.invoke("Retrying OpenCode connection (attempt $attempt/$maxAttempts)...")
                    delay(2000L * attempt)
                }
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string() ?: ""
                        Log.e(TAG, "OpenCode API error (attempt $attempt): ${response.code} - $errBody")
                        if (response.code in listOf(429, 500, 502, 503, 504) && attempt < maxAttempts) {
                            lastException = IOException("Server status ${response.code}: $errBody")
                            return@use
                        }
                        throw IOException("Server responded with code ${response.code}: $errBody")
                    }

                    val bodySource = response.body?.source() ?: throw IOException("Empty response from OpenCode")
                    val contentBuf = StringBuilder()
                    val toolCalls = JSONArray()
                    var done = false

                    while (!done) {
                        val line = bodySource.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break

                        val chunk = try {
                            JSONObject(payload)
                        } catch (e: Exception) {
                            continue
                        }
                        val choices = chunk.optJSONArray("choices")
                        if (choices == null || choices.length() == 0) continue
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")

                        if (delta != null) {
                            if (delta.has("content") && !delta.isNull("content")) {
                                contentBuf.append(delta.getString("content"))
                                // Only surface text when this turn has no tool calls yet,
                                // so intermediate turns never flash on screen.
                                if (toolCalls.length() == 0) {
                                    onPartialContent?.invoke(contentBuf.toString())
                                }
                            }
                            val tcArray = delta.optJSONArray("tool_calls")
                            if (tcArray != null) {
                                for (i in 0 until tcArray.length()) {
                                    val tc = tcArray.getJSONObject(i)
                                    val index = tc.optInt("index", 0)
                                    while (toolCalls.length() <= index) toolCalls.put(JSONObject())
                                    val target = toolCalls.getJSONObject(index)
                                    if (tc.has("id") && !tc.isNull("id")) target.put("id", tc.getString("id"))
                                    if (tc.has("type") && !tc.isNull("type")) target.put("type", tc.getString("type"))
                                    val fn = tc.optJSONObject("function")
                                    if (fn != null) {
                                        val tFn = if (target.has("function")) target.getJSONObject("function")
                                        else JSONObject().also { target.put("function", it) }
                                        if (fn.has("name") && !fn.isNull("name")) tFn.put("name", fn.getString("name"))
                                        if (fn.has("arguments") && !fn.isNull("arguments")) {
                                            tFn.put("arguments", tFn.optString("arguments", "") + fn.getString("arguments"))
                                        }
                                    }
                                }
                            }
                        }

                        if (choice.has("finish_reason") && !choice.isNull("finish_reason")) {
                            done = true
                        }
                    }

                    return@withContext JSONObject().apply {
                        put("role", "assistant")
                        val contentStr = contentBuf.toString()
                        if (contentStr.isNotEmpty()) put("content", contentStr)
                        if (toolCalls.length() > 0) put("tool_calls", toolCalls)
                    }
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "OpenCode stream attempt $attempt failed: ${e.message}")
            }
        }
        throw lastException ?: IOException("Failed to connect to OpenCode after $maxAttempts attempts")
    }

    /**
     * Sends a request to Gemini 3.5 Flash with the student's database context.
     * @param userQuery The question/command from the user.
     * @param apiKey The Gemini API Key (either user provided or from BuildConfig).
     * @param courses Cached courses in the local DB.
     * @param activities Cached activities in the local DB.
     * @param notes Cached notes in the local DB.
     * @param rules Existing notifications in the local DB.
     */
    suspend fun chatWithAi(
        userQuery: String,
        apiKey: String,
        courses: List<Course>,
        activities: List<ActivityItem>,
        rules: List<NotificationRule>,
        allAccounts: List<MoodleAccount>,
        useOpenCode: Boolean = false,
        openCodeApiKey: String = "",
        openCodeModel: String = "FREE: Big Pickle",
        deactivateThinking: Boolean = true,
        sgaAccessToken: String = "",
        sgaRefreshToken: String = "",
        sgaSessionPayload: String = "",
        sgaUser: String = "",
        sgaPass: String = "",
        sgaSessionValid: Boolean = true,
        chatHistory: List<com.example.ui.viewmodel.ChatMessage> = emptyList(),
        onProgress: ((String) -> Unit)? = null,
        onPartialReply: ((String) -> Unit)? = null,
        onSgaTokenRefreshed: ((newAccess: String, newRefresh: String) -> Unit)? = null
    ): ChatResult = withContext(Dispatchers.IO) {
        onProgress?.invoke("Thinking...")
        var currentSgaAccessToken = sgaAccessToken
        var currentSgaRefreshToken = sgaRefreshToken
        val maxHistoryLength = 12
        val recentHistory = chatHistory.takeLast(maxHistoryLength)

        // System Instructions that give the AI context of Moodle, alarms, and its capabilities
        val coursesContext = JSONArray().apply {
            courses.forEach {
                put(JSONObject().apply {
                    put("id", it.moodleCourseId)
                    put("fullName", it.fullName)
                    put("shortName", it.shortName)
                })
            }
        }.toString()

        val activitiesContext = JSONArray().apply {
            activities.forEach {
                put(JSONObject().apply {
                    put("id", it.moodleActivityId)
                    put("courseId", it.courseId)
                    put("name", it.name)
                    put("type", it.moduleType)
                    put("dueDate", it.dueDate?.let { d -> 
                        // Format as readable date or timestamp
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(d * 1000))
                    } ?: "No due date")
                    put("status", it.status)
                    put("grade", it.grade ?: "Not graded")
                    put("maxGrade", it.maxGrade ?: "100")
                })
            }
        }.toString()

        val rulesContext = JSONArray().apply {
            rules.forEach {
                put(JSONObject().apply {
                    put("id", it.id)
                    put("title", it.title)
                    put("body", it.body)
                    put("triggerType", it.triggerType)
                    put("triggerCode", it.triggerCode)
                    put("timeScheduled", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(it.timeScheduled)))
                    put("isEnabled", it.isEnabled)
                })
            }
        }.toString()

        val currentDateTimeString = java.text.SimpleDateFormat(
            "EEEE, MMMM dd, yyyy HH:mm (z)", 
            java.util.Locale.US
        ).format(java.util.Date())

        val accountInfoText = if (allAccounts.isNotEmpty()) {
            val accountsList = coroutineScope {
                allAccounts.mapIndexed { index, acc ->
                    async(Dispatchers.IO) {
                        val token = acc.token ?: "demo_token_1234567890"
                        val userId = if (acc.id == 9999) 12345 else {
                            getCachedUserId(acc.moodleUrl, token)
                        }
                        """
                        Account #${index + 1}:
                        - Full Name: ${acc.fullName}
                        - Username: ${acc.username}
                        - Base URL: ${acc.moodleUrl}
                        - Token: $token
                        - User ID: $userId
                        - Active: ${acc.isActive}
                        """.trimIndent()
                    }
                }.awaitAll()
            }
            val accountsText = accountsList.joinToString("\n\n")
            "CONNECTED MOODLE ACCOUNTS:\n$accountsText"
        } else {
            "NO MOODLE ACCOUNTS LOGGED IN. Remind user to log in or switch to Demo Mode in Configurations."
        }

        val systemInstructionText = """
            You are "Moodle Companion AI", a brilliant, supportive, and context-aware academic assistant.
            You are integrated directly into an Android student portal. You have access to the user's Moodle databases, courses, grades, activities, and notifications.
            
            You have access to the following student data in REAL-TIME:
            1. COURSES: $coursesContext
            2. ACTIVITIES & GRADES: $activitiesContext
            3. SCHEDULED ALARMS/NOTIFICATIONS: $rulesContext
            4. CURRENT LOCAL TIME (TODAY): $currentDateTimeString
            
            $accountInfoText

            ACTIVE UNEMI SGA SESSION:
            ${if (sgaAccessToken.isNotEmpty()) """
            - SGA Session Status: ${if (sgaSessionValid) "Active" else "Expired (renewal failed)"}
            - DIRECT EXECUTION RULE: An active SGA session is configured${if (!sgaSessionValid) ", but it is currently expired and could not be renewed automatically" else ""}. The app automatically injects and maintains the Authorization Bearer token and performs silent re-authentication on 401 errors. When calling UNEMI SGA endpoints using 'executeApiEndpoint', pass 'headersJson': '{}' or omit 'headersJson'. If an SGA API call still returns 401/Unauthorized after automatic refresh, inform the user: "Your SGA session expired and couldn't be renewed automatically — please re-authenticate in Configurations." Never invent, fabricate, or "confirm" SGA data unless explicitly returned by an actual API call.
            """.trimIndent() else "No active UNEMI SGA session stored. If user asks for SGA info without logging in, tell them to log in via Configurations or supply credentials."}
            
            CURRENT SGA SESSION CONTEXT (REAL ids from the user's authenticated session — use these exact values for 'perfil_id' in token/change/career and 'periodo_id' in token/change/academic_period; NEVER invent, guess, or hardcode ids):
            ${buildSgaSessionContext(sgaSessionPayload)}
            
            CRITICAL DIRECTIVE FOR UNEMI SGA API:
            - UNEMI SGA student endpoints are 100% STATELESS REST APIs located at 'https://sga.unemi.edu.ec/api/1.0/jwt/...'.
            - DO NOT hallucinate, fabricate, or claim that SGA requires WebSockets, cookies, a WebView, JavaScript execution, or a 'changetoken' link.
            - DO NOT claim that tokens are 'inactive' or that you cannot read SGA data.
            - When the user asks for SGA information (e.g. grades/notas, malla curricular, materias, class schedule, exam dates/horarioexamen, finances/finanzas, attendance/asistencia, calendar, notifications, events), IMMEDIATELY invoke the 'executeApiEndpoint' function with the exact URL!
            - Base host for all data endpoints is ALWAYS 'https://sga.unemi.edu.ec/api/1.0/jwt/'. (Login host 'sgaestudiante.unemi.edu.ec' is ONLY for initial login).
            
            MOODLE API ENDPOINTS DOCUMENTATION:
            You can use the 'executeApiEndpoint' function calling tool to fetch real-time, missing, or more detailed Moodle information directly:
            - POST {baseUrl}/login/token.php?username={username}&password={password}&service=moodle_mobile_app (Returns token)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=gradereport_user_get_grade_items&moodlewsrestformat=json&courseid={courseId}&userid={userId} (Gets course grades)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_quiz_get_quizzes_by_courses&moodlewsrestformat=json&courseids[0]={courseId} (Gets quiz metadata: sumgrades/maxGrade, timeclose, etc.)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}/wsfunction=mod_quiz_get_user_attempts&moodlewsrestformat=json&quizid={quizId}&userid={userId}&includepreviews=0 (Gets quiz attempts and grades)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_assign_get_assignments&moodlewsrestformat=json&courseids[0]={courseId} (Gets assignments listing and due dates)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_assign_get_submission_status&moodlewsrestformat=json&assignid={assignId}&userid={userId} (Gets assignment submission status and grade)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_forum_get_forums_by_courses&moodlewsrestformat=json&courseids[0]={courseId} (Gets forums listing, where 'grade_forum' is the maximum grade)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_course_get_contents&moodlewsrestformat=json&courseid={courseId} (Gets course modules and dates)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_calendar_get_action_events_by_courses&moodlewsrestformat=json&courseids[0]={courseId} (Gets calendar events, overdue items, exam/quiz close dates)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_message_get_messages&moodlewsrestformat=json&useridto={userId}&type=both (Gets direct messages and system notifications)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_resource_get_resources_by_courses&moodlewsrestformat=json&courseids[0]={courseId} (Gets course file/PDF resources)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_enrol_get_enrolled_users&moodlewsrestformat=json&courseid={courseId} (Gets enrolled users: classmates, professors, roles, groups, emails)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=gradereport_overview_get_course_grades&moodlewsrestformat=json&userid={userId} (Gets overall summary grades for all enrolled courses)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_group_get_course_user_groups&moodlewsrestformat=json&courseid={courseId}&userid={userId} (Gets your group membership for a specific course)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_enrol_get_users_courses&moodlewsrestformat=json&userid={userId} (Gets list of courses enrolled by user)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_completion_get_activities_completion_status&moodlewsrestformat=json&courseid={courseId}&userid={userId} (Gets per-activity completion state, tracking, and completionsubmit status)
            - POST {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_message_send_instant_messages&moodlewsrestformat=json (Sends direct messages, requires POST body parameters 'messages[0][touserid]', 'messages[0][text]', etc.)
            - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_feedback_get_feedbacks_by_courses&moodlewsrestformat=json&courseids[0]={courseId} (Gets surveys/feedbacks by course IDs)

            UNEMI SGA ESTUDIANTE API ENDPOINTS DOCUMENTATION:
            You can also use 'executeApiEndpoint' to interact directly with UNEMI SGA (https://sga.unemi.edu.ec / https://sgaestudiante.unemi.edu.ec):
            - POST https://sgaestudiante.unemi.edu.ec/api/auth/login.json (Login: body {"username":"...", "password":"...", "clientNavegador":"Chrome 126", "clientOS":"Android", "clientScreensize":"1080x2400"})
            - POST https://sga.unemi.edu.ec/api/1.0/jwt/token/refresh (Refresh token: body {"refresh":"<refreshToken>"})
            - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/notas/{inscripcion_id}/ (Full academic record, grades, levels, approved/pending subjects)
            - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/malla (Curriculum/malla, credits completed, required credits, level subjects)
            - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/materias (Current enrolled subjects, teacher info, current GPA, total credits)
            - POST https://sga.unemi.edu.ec/api/1.0/jwt/alumno/horario (Class timetable: body {"action":"loadInit"})
            - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/horarioexamen?action=horario (Exam schedule, times, classrooms, test keys)
            - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/asistencia/{matricula_id} (Attendance overview per subject)
            - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/finanzas (Financial rubros, payment amounts, due dates, invoice reports)
            - POST https://sga.unemi.edu.ec/api/1.0/jwt/alumno/general/data (Calendar & invoices: body {"action":"detail_calenar_student", "id":"<matricula_id>"})
            - POST https://sga.unemi.edu.ec/api/1.0/jwt/alumno/panel (Dashboard modules eModules, pending values, personal profile)
            - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/notificacion (Student notifications list)
            - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/evento?opc_select=2 (Upcoming university events)
            - GET/POST https://sga.unemi.edu.ec/api/1.0/jwt/alumno/tutoria_academica (Academic tutoring requests: action "loadSolicitudes" or "loadMaterias")
            - POST https://sga.unemi.edu.ec/api/1.0/jwt/token/change/academic_period (Switch academic period: body {"refresh":"...", "periodo_id":<id>})
            - POST https://sga.unemi.edu.ec/api/1.0/jwt/token/change/career (Switch career: body {"refresh":"...", "perfil_id":<id>})
            Note for SGA Endpoints: Pass 'headersJson': '{"Authorization": "Bearer <access_token>"}' when calling JWT endpoints.

            GRADE & STATUS EXTRACTION RULES:
            - Quiz (Cuestionario):
              * Check 'completiondata.state' in core_course_get_contents. State 0 = incomplete (Pending/No hecho), State > 0 = complete (Submitted/Graded).
              * If completed, check for 'graderaw' in gradereport_user_get_grade_items. If graderaw is present and not null, status is 'graded' (with grade); if missing or null, status is 'submitted' (Hecho, nota no liberada).
              * Max grade is 'sumgrades' in mod_quiz_get_quizzes_by_courses, obtained grade is 'graderaw'.
            - Assignment (Tarea):
              * Check fields in gradereport_user_get_grade_items:
                + 'gradedatesubmitted' is null -> 'pending' (No entregado)
                + 'gradedatesubmitted' is not null, 'gradedategraded' is null -> 'submitted' (Entregado, sin calificar)
                + 'gradedatesubmitted' and 'gradedategraded' are not null -> 'graded' (Calificado), grade is 'graderaw'.
              * Max grade is 'grademax' or parsed feedback 'gradefordisplay' (after /).
            - Forum (Foro): Max grade from 'grade_forum' in mod_forum_get_forums_by_courses; Obtained grade from 'graderaw' in gradereport_user_get_grade_items.
            - Any item: Max grade is 'grademax', Obtained grade is 'graderaw'.

            Your capabilities:
            - Answer questions about their courses, upcoming deadlines, exam dates, syllabus content, grades, and academic performance across all connected Moodle accounts.
            - Analyze which activities are due soon (sort by closing deadlines across all profiles) and help them prioritize.
            - Calculate grade averages, remaining required points, or show grade statistics.
            - Create or modify custom coded notifications/alarms. The app runs a sandboxed, robust Kotlin-based script interpreter locally. If they ask to set a reminder/alarm (e.g., "Remind me 2 hours before Quiz 1" or "set an alarm always two hours before any closing test"), you must generate a SCHEDULE_NOTIFICATION action with a valid local script in `triggerCode`.
            
            ACTION EXECUTIONS:
            If the user asks you to set an alarm/reminder, you can trigger these actions on the phone. To trigger an action, append a single JSON block inside a markdown code block of type "json" at the VERY END of your response.
            
            To SCHEDULE A NOTIFICATION/ALARM:
            Identify the action parameters. For `triggerCode` custom rules (e.g., event monitoring, grades, activity submissions), always set `timeScheduled` to 0 or current timestamp so monitoring begins immediately. For fixed time reminders without script rules, compute target epoch timestamp. Remember the current year is 2026.
            Use the current local time context provided or calculate appropriate timestamps.
            
            For triggerCode, write a short script that our parser will interpret. It supports:
            - Loop syntax: `for activity in activities { ... }`, `for quiz in quizzes { ... }`, `for assignment in assignments { ... }`, `for course in courses { ... }`
            - Conditionals: `if (condition) { ... } else { ... }`
            - Operators: `==`, `!=`, `<`, `>`, `<=`, `>=`, `&&`, `||`, `!`, `-`, `+`, `*`, `/`
            - Functions:
              * `notify("title", "body")` for standard notifications
              * `alarm("title", "body")` for loud ringing alarms with dismissal UI on screen
              * `hours(h)`, `days(d)`, `minutes(m)` (converts hours/days/minutes to milliseconds)
              * `isToday(timestamp)`, `isTomorrow(timestamp)`, `isYesterday(timestamp)` (checks if timestamp is today, tomorrow, or yesterday)
              * `startOfDay(timestamp)`, `endOfDay(timestamp)` (returns start/end of day timestamp, e.g. startOfDay(now))
            - Properties:
              * `activity.name`, `activity.moduleType`, `activity.dueDate` (milliseconds), `activity.status` ("submitted", "not_submitted", "graded"), `activity.grade`, `activity.maxGrade`
              * `course.fullName`, `course.shortName`, `course.moodleCourseId`
              * `now` (current epoch milliseconds)
            
            EXAMPLES OF VALID SCRIPTS:
            1. "always set an alarm two hours before any closing test (quiz)":
               `for quiz in quizzes { if (quiz.dueDate != null) { val timeLeft = quiz.dueDate - now; if (timeLeft > 0 && timeLeft <= hours(2)) { alarm("Quiz Closing Soon", quiz.name + " closes in less than 2 hours!") } } }`
            2. "notify 5 days before any exam":
               `for activity in activities { if (activity.moduleType == "quiz" && activity.dueDate != null) { val timeLeft = activity.dueDate - now; if (timeLeft > 0 && timeLeft <= days(5)) { notify("Exam Coming Up", activity.name + " closes in less than 5 days!") } } }`
            3. "set an alarm 1 hour before Assignment 2":
               `for assignment in assignments { if (assignment.name == "Assignment 2" && assignment.dueDate != null) { val timeLeft = assignment.dueDate - now; if (timeLeft > 0 && timeLeft <= hours(1)) { alarm("Assignment Due Soon", "Assignment 2 closes in 1 hour!") } } }`
            4. "remind me if I haven't submitted S15-COMPONENTE PRÁCTICO_2 due today":
               `for activity in activities { if (activity.name == "S15-COMPONENTE PRÁCTICO_2" && activity.dueDate != null) { if (isToday(activity.dueDate)) { if (activity.status != "submitted" && activity.status != "graded") { alarm("⚠️ ¡ENTREGA PENDIENTE!", "S15-COMPONENTE PRÁCTICO_2 aún no está entregada y vence hoy!") } } } }`
            
            Format:
            ```json
            {
              "action": "schedule_notification",
              "title": "Rule Name/Title",
              "body": "Friendly description of what this rule does",
              "triggerCode": "for quiz in quizzes { if (quiz.dueDate != null) { val timeLeft = quiz.dueDate - now; if (timeLeft > 0 && timeLeft <= hours(2)) { alarm(\"Quiz Closing Soon\", quiz.name + \" closes soon!\") } } }",
              "ruleType": "ALARM", 
              "timeScheduled": 1784918200000 
            }
            ```
            Note: `ruleType` can be "ALARM" (loud ringing and overlay) or "NOTIFICATION" (quiet reminder). Choose carefully based on what the user asked for. Use "ALARM" if they say "alarm", or "NOTIFICATION" if they say "notification", "reminder", or "notify".
            
            Keep your main text friendly, informative, encouraging, and formatted in clean Markdown.
            Reference specific course names and deadlines. If they have no grades or activities, advise them to sync their accounts or switch to Demo Mode in Configurations.
        """.trimIndent()

        if (useOpenCode) {
            if (openCodeApiKey.trim().isEmpty()) {
                return@withContext ChatResult(
                    reply = "Error: OpenCode API Key is missing. Please configure it in Configurations.",
                    action = null
                )
            }
            try {
                val url = "https://opencode.ai/zen/v1/chat/completions"
                val adjustedSystemInstructionText = if (deactivateThinking) {
                    "$systemInstructionText\n\nCRITICAL: Do NOT output thinking blocks or <think> tags. Speak directly and concisely to the user."
                } else {
                    systemInstructionText
                }

                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", adjustedSystemInstructionText)
                    })
                    recentHistory.forEach { msg ->
                        put(JSONObject().apply {
                            put("role", if (msg.sender == "user") "user" else "assistant")
                            put("content", msg.text.take(MAX_HISTORY_CHARS_PER_MSG))
                        })
                    }
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userQuery)
                    })
                }

                val resolvedModel = when (openCodeModel.trim()) {
                    "FREE: Big Pickle", "big-pickle" -> "big-pickle"
                    "nemutron 3 ultra free", "nemotron 3 ultra free", "nemotron-3-ultra-free" -> "nemotron-3-ultra-free"
                    else -> openCodeModel.trim()
                }

                // Define OpenAI tools
                val openAiToolsArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", "executeApiEndpoint")
                            put("description", "Execute an HTTP/HTTPS request to any Moodle API endpoint, web service, or university URL to dynamically fetch or test for needed information (such as grades, course structures, syllabus details, or user data) until the needed data is gathered.")
                            put("parameters", JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("url", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "The full URL of the API endpoint or web resource to test/request")
                                    })
                                    put("method", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "The HTTP method (e.g., GET, POST, PUT, DELETE)")
                                    })
                                    put("headersJson", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "Optional headers in JSON format, e.g. {\"Authorization\": \"Bearer token\"}")
                                    })
                                    put("bodyJson", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "Optional request body in JSON or raw format for write requests")
                                    })
                                })
                                put("required", JSONArray().apply {
                                    put("url")
                                    put("method")
                                })
                            })
                        })
                    })
                }

                var openCodeLoopCount = 0
                val maxOpenCodeLoops = 15
                var openCodeFinalReply = ""

                while (openCodeLoopCount < maxOpenCodeLoops) {
                    val requestJson = JSONObject().apply {
                        put("model", resolvedModel)
                        put("messages", messagesArray)
                        put("temperature", 0.3)
                        put("tools", openAiToolsArray)
                        put("thinking", JSONObject().apply { put("type", "disabled") })
                        put("stream", true)
                    }

                    Log.d(TAG, "Sending request to OpenCode (loop ${openCodeLoopCount + 1}): model=$resolvedModel")

                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val requestBody = requestJson.toString().toRequestBody(mediaType)
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $openCodeApiKey")
                        .post(requestBody)
                        .build()

                    // Stream the response: text deltas are forwarded to the UI as they
                    // arrive (final answer only — tool-call turns have no visible text).
                    val messageObj = executeOpenCodeStream(request, onProgress) { partialText ->
                        onPartialReply?.invoke(partialText)
                    }
                    val toolCalls = messageObj.optJSONArray("tool_calls")

                    if (messageObj.optString("content", "").isBlank() && (toolCalls == null || toolCalls.length() == 0)) {
                        return@withContext ChatResult(
                            reply = "No response candidates returned from OpenCode.",
                            action = null
                        )
                    }

                    // We MUST add the model's assistant turn to the messages history.
                    messagesArray.put(messageObj)

                    if (toolCalls != null && toolCalls.length() > 0) {
                        val toolIndices = (0 until toolCalls.length()).toList()
                        val toolResponseObjects = coroutineScope {
                            toolIndices.map { i ->
                                async(Dispatchers.IO) {
                                    val toolCall = toolCalls.getJSONObject(i)
                                    val callId = toolCall.getString("id")
                                    val functionObj = toolCall.getJSONObject("function")
                                    val name = functionObj.getString("name")
                                    val argsStr = functionObj.optString("arguments", "{}")
                                    val args = try {
                                        JSONObject(argsStr)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Invalid tool call arguments: $argsStr", e)
                                        JSONObject()
                                    }

                                    Log.i(TAG, "OpenCode requested Function Call: $name (id: $callId) with args: $args")

                                    val result = if (name == "executeApiEndpoint") {
                                        val requestUrl = args.getString("url")
                                        val friendlyTask = getFriendlyWsFunction(requestUrl)
                                        if (friendlyTask.isNotEmpty()) {
                                            onProgress?.invoke("Moodle AI is $friendlyTask...")
                                        } else {
                                            onProgress?.invoke("Moodle AI is reading endpoints...")
                                        }
                                        val requestMethod = args.optString("method", "GET")
                                        var requestHeaders = args.optString("headersJson", "")
                                        val requestBodyStr = args.optString("bodyJson", "")

                                        // Auto-inject / overwrite active SGA Bearer token if querying SGA endpoints
                                        if ((requestUrl.contains("sga.unemi.edu.ec") || requestUrl.contains("sgaestudiante.unemi.edu.ec")) && currentSgaAccessToken.isNotEmpty()) {
                                            try {
                                                val hObj = if (requestHeaders.isNotBlank()) JSONObject(requestHeaders) else JSONObject()
                                                hObj.put("Authorization", "Bearer $currentSgaAccessToken")
                                                requestHeaders = hObj.toString()
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error updating SGA token header in OpenCode", e)
                                            }
                                        }

                                        performHttpRequest(
                                            url = requestUrl,
                                            method = requestMethod,
                                            headersJson = requestHeaders,
                                            bodyJson = requestBodyStr,
                                            sgaAccessTokenProvider = { currentSgaAccessToken },
                                            sgaRefreshTokenProvider = { currentSgaRefreshToken },
                                            sgaUserProvider = { sgaUser },
                                            sgaPassProvider = { sgaPass },
                                            onSgaRefreshed = { newAccess, newRefresh ->
                                                currentSgaAccessToken = newAccess
                                                currentSgaRefreshToken = newRefresh
                                                onSgaTokenRefreshed?.invoke(newAccess, newRefresh)
                                            }
                                        )
                                    } else {
                                        "Unknown function name: $name"
                                    }

                                    Log.d(TAG, "OpenCode tool call result: $result")

                                    val truncatedContent = if (result.length > 10000) {
                                        result.substring(0, 10000) + "\n...[Payload truncated to prevent OpenCode prompt overflow]"
                                    } else {
                                        result
                                    }

                                    JSONObject().apply {
                                        put("role", "tool")
                                        put("tool_call_id", callId)
                                        put("name", name)
                                        put("content", truncatedContent)
                                    }
                                }
                            }.awaitAll()
                        }

                        for (toolRes in toolResponseObjects) {
                            messagesArray.put(toolRes)
                        }
                        openCodeLoopCount++
                    } else {
                        // No tool calls, we have the final text!
                        var contentText = messageObj.optString("content", "")
                        if (contentText.isBlank()) {
                            contentText = messageObj.optString("reasoning_content", "")
                                .ifBlank { messageObj.optString("reasoning", "") }
                                .ifBlank { messageObj.optString("reasoning_details", "") }
                        }
                        openCodeFinalReply = contentText
                        break
                    }
                }

                if (openCodeFinalReply.isBlank()) {
                    if (openCodeLoopCount >= maxOpenCodeLoops) {
                        try {
                            onProgress?.invoke("Moodle AI is summarizing findings...")
                            val explanationMessagesArray = JSONArray()
                            for (i in 0 until messagesArray.length()) {
                                explanationMessagesArray.put(messagesArray.getJSONObject(i))
                            }
                            explanationMessagesArray.put(JSONObject().apply {
                                put("role", "user")
                                put("content", "CRITICAL: You have executed 15 Moodle API requests and reached the maximum execution step limit of this turn. Please summarize what endpoints you checked, what you discovered, and explain to the user that you hit the limit but they can say 'continue' or write another message if they want you to keep searching or complete the task.")
                            })

                            val requestJson = JSONObject().apply {
                                put("model", resolvedModel)
                                put("messages", explanationMessagesArray)
                                put("temperature", 0.5)
                                put("thinking", JSONObject().apply { put("type", "disabled") })
                            }

                            val mediaType = "application/json; charset=utf-8".toMediaType()
                            val requestBody = requestJson.toString().toRequestBody(mediaType)
                            val request = Request.Builder()
                                .url(url)
                                .addHeader("Authorization", "Bearer $openCodeApiKey")
                                .post(requestBody)
                                .build()

                            val responseBodyStr = executeOpenCodeRequest(request, onProgress)
                            if (responseBodyStr.isNotEmpty()) {
                                val responseJson = JSONObject(responseBodyStr)
                                val choices = responseJson.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val choice = choices.getJSONObject(0)
                                    val messageObj = choice.optJSONObject("message") ?: JSONObject()
                                    var text = messageObj.optString("content", "")
                                    if (text.isBlank()) {
                                        text = messageObj.optString("reasoning_content", "")
                                            .ifBlank { messageObj.optString("reasoning", "") }
                                            .ifBlank { messageObj.optString("reasoning_details", "") }
                                    }
                                    openCodeFinalReply = text
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to get OpenCode final loop explanation", e)
                        }

                        if (openCodeFinalReply.isBlank()) {
                            openCodeFinalReply = "I have queried the university platform 15 times to fetch your information, but reached the execution step limit before finishing. Please write a message like 'continue' so I can keep searching and complete your request!"
                        }
                    } else {
                        openCodeFinalReply = "I completed processing your request, but received an empty response from the model. Please try sending your message again or rephrasing your query."
                    }
                }

                if (deactivateThinking) {
                    openCodeFinalReply = openCodeFinalReply.replace("(?i)<think>.*?</think>".toRegex(RegexOption.DOT_MATCHES_ALL), "").trim()
                    openCodeFinalReply = openCodeFinalReply.replace("(?i)<think>".toRegex(), "")
                    openCodeFinalReply = openCodeFinalReply.replace("(?i)</think>".toRegex(), "").trim()
                }

                val parsedResult = extractActionBlock(openCodeFinalReply)
                return@withContext parsedResult

            } catch (e: Exception) {
                Log.e(TAG, "Error chatting with OpenCode", e)
                val errMsg = e.localizedMessage ?: e.message ?: "Connection timeout"
                val isTimeout = errMsg.contains("timeout", ignoreCase = true) || 
                                errMsg.contains("SocketTimeout", ignoreCase = true) ||
                                e is java.net.SocketTimeoutException ||
                                e is java.io.InterruptedIOException

                val replyText = if (isTimeout) {
                    "⚠️ OpenCode Connection Timeout\n\n" +
                    "The OpenCode AI server took longer than expected to respond or experienced high network latency.\n\n" +
                    "💡 Quick Solutions:\n" +
                    "1. Tap 'Send' or resend your message to try again.\n" +
                    "2. Go to Configurations (⚙️) and switch OpenCode Model to 'big-pickle' (or another fast model).\n" +
                    "3. Turn off OpenCode in Configurations to use the standard Gemini 3.5 Flash engine."
                } else {
                    "Error: Could not connect to OpenCode. Details: $errMsg"
                }

                return@withContext ChatResult(
                    reply = replyText,
                    action = null
                )
            }
        }

        if (apiKey.trim().isEmpty()) {
            return@withContext ChatResult(
                reply = "Error: Gemini API Key is missing. Please insert your API Key in the Settings page.",
                action = null
            )
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent?key=$apiKey"

            // Build Context Data Strings
            val coursesContext = JSONArray().apply {
                courses.forEach {
                    put(JSONObject().apply {
                        put("id", it.moodleCourseId)
                        put("fullName", it.fullName)
                        put("shortName", it.shortName)
                    })
                }
            }.toString()

            val activitiesContext = JSONArray().apply {
                activities.forEach {
                    put(JSONObject().apply {
                        put("id", it.moodleActivityId)
                        put("courseId", it.courseId)
                        put("name", it.name)
                        put("type", it.moduleType)
                        put("dueDate", it.dueDate?.let { d -> 
                            // Format as readable date or timestamp
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(d * 1000))
                        } ?: "No due date")
                        put("status", it.status)
                        put("grade", it.grade ?: "Not graded")
                        put("maxGrade", it.maxGrade ?: "100")
                    })
                }
            }.toString()



            val rulesContext = JSONArray().apply {
                rules.forEach {
                    put(JSONObject().apply {
                        put("id", it.id)
                        put("title", it.title)
                        put("body", it.body)
                        put("triggerType", it.triggerType)
                        put("triggerCode", it.triggerCode)
                        put("timeScheduled", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(it.timeScheduled)))
                        put("isEnabled", it.isEnabled)
                    })
                }
            }.toString()

            val currentDateTimeString = java.text.SimpleDateFormat(
                "EEEE, MMMM dd, yyyy HH:mm (z)", 
                java.util.Locale.US
            ).format(java.util.Date())

            val accountInfoText = if (allAccounts.isNotEmpty()) {
                val accountsText = allAccounts.mapIndexed { index, acc ->
                    val token = acc.token ?: "demo_token_1234567890"
                    val userId = if (acc.id == 9999) 12345 else {
                        try {
                            MoodleApiClient.fetchSiteInfo(acc.moodleUrl, token).userId
                        } catch (e: Exception) {
                            0
                        }
                    }
                    """
                    Account #${index + 1}:
                    - Full Name: ${acc.fullName}
                    - Username: ${acc.username}
                    - Base URL: ${acc.moodleUrl}
                    - Token: $token
                    - User ID: $userId
                    - Active: ${acc.isActive}
                    """.trimIndent()
                }.joinToString("\n\n")
                "CONNECTED MOODLE ACCOUNTS:\n$accountsText"
            } else {
                "NO MOODLE ACCOUNTS LOGGED IN. Remind user to log in or switch to Demo Mode in Configurations."
            }

            // System Instructions that give the AI context of Moodle, alarms, and its capabilities
            val systemInstructionText = """
                You are "Moodle Companion AI", a brilliant, supportive, and context-aware academic assistant.
                You are integrated directly into an Android student portal. You have access to the user's Moodle databases, courses, grades, activities, and notifications.
                
                You have access to the following student data in REAL-TIME:
                1. COURSES: $coursesContext
                2. ACTIVITIES & GRADES: $activitiesContext
                3. SCHEDULED ALARMS/NOTIFICATIONS: $rulesContext
                4. CURRENT LOCAL TIME (TODAY): $currentDateTimeString
                
                $accountInfoText
                
                MOODLE API ENDPOINTS DOCUMENTATION:
                You can use the 'executeApiEndpoint' function calling tool to fetch real-time, missing, or more detailed Moodle information directly:
                - POST {baseUrl}/login/token.php?username={username}&password={password}&service=moodle_mobile_app (Returns token)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=gradereport_user_get_grade_items&moodlewsrestformat=json&courseid={courseId}&userid={userId} (Gets course grades)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_quiz_get_quizzes_by_courses&moodlewsrestformat=json&courseids[0]={courseId} (Gets quiz metadata: sumgrades/maxGrade, timeclose, etc.)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}/wsfunction=mod_quiz_get_user_attempts&moodlewsrestformat=json&quizid={quizId}&userid={userId}&includepreviews=0 (Gets quiz attempts and grades)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_assign_get_assignments&moodlewsrestformat=json&courseids[0]={courseId} (Gets assignments listing and due dates)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_assign_get_submission_status&moodlewsrestformat=json&assignid={assignId}&userid={userId} (Gets assignment submission status and grade)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_forum_get_forums_by_courses&moodlewsrestformat=json&courseids[0]={courseId} (Gets forums listing, where 'grade_forum' is the maximum grade)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_course_get_contents&moodlewsrestformat=json&courseid={courseId} (Gets course modules and dates)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_calendar_get_action_events_by_courses&moodlewsrestformat=json&courseids[0]={courseId} (Gets calendar events, overdue items, exam/quiz close dates)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_message_get_messages&moodlewsrestformat=json&useridto={userId}&type=both (Gets direct messages and system notifications)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_resource_get_resources_by_courses&moodlewsrestformat=json&courseids[0]={courseId} (Gets course file/PDF resources)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_enrol_get_enrolled_users&moodlewsrestformat=json&courseid={courseId} (Gets enrolled users: classmates, professors, roles, groups, emails)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=gradereport_overview_get_course_grades&moodlewsrestformat=json&userid={userId} (Gets overall summary grades for all enrolled courses)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_group_get_course_user_groups&moodlewsrestformat=json&courseid={courseId}&userid={userId} (Gets your group membership for a specific course)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_enrol_get_users_courses&moodlewsrestformat=json&userid={userId} (Gets list of courses enrolled by user)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_completion_get_activities_completion_status&moodlewsrestformat=json&courseid={courseId}&userid={userId} (Gets per-activity completion state, tracking, and completionsubmit status)
                - POST {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=core_message_send_instant_messages&moodlewsrestformat=json (Sends direct messages, requires POST body parameters 'messages[0][touserid]', 'messages[0][text]', etc.)
                - GET {baseUrl}/webservice/rest/server.php?wstoken={token}&wsfunction=mod_feedback_get_feedbacks_by_courses&moodlewsrestformat=json&courseids[0]={courseId} (Gets surveys/feedbacks by course IDs)

                UNEMI SGA ESTUDIANTE API ENDPOINTS DOCUMENTATION:
                You can also use 'executeApiEndpoint' to interact directly with UNEMI SGA (https://sga.unemi.edu.ec / https://sgaestudiante.unemi.edu.ec):
                - POST https://sgaestudiante.unemi.edu.ec/api/auth/login.json (Login: body {"username":"...", "password":"...", "clientNavegador":"Chrome 126", "clientOS":"Android", "clientScreensize":"1080x2400"})
                - POST https://sga.unemi.edu.ec/api/1.0/jwt/token/refresh (Refresh token: body {"refresh":"<refreshToken>"})
                - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/notas/{inscripcion_id}/ (Full academic record, grades, levels, approved/pending subjects)
                - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/malla (Curriculum/malla, credits completed, required credits, level subjects)
                - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/materias (Current enrolled subjects, teacher info, current GPA, total credits)
                - POST https://sga.unemi.edu.ec/api/1.0/jwt/alumno/horario (Class timetable: body {"action":"loadInit"})
                - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/horarioexamen?action=horario (Exam schedule, times, classrooms, test keys)
                - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/asistencia/{matricula_id} (Attendance overview per subject)
                - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/finanzas (Financial rubros, payment amounts, due dates, invoice reports)
                - POST https://sga.unemi.edu.ec/api/1.0/jwt/alumno/general/data (Calendar & invoices: body {"action":"detail_calenar_student", "id":"<matricula_id>"})
                - POST https://sga.unemi.edu.ec/api/1.0/jwt/alumno/panel (Dashboard modules eModules, pending values, personal profile)
                - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/notificacion (Student notifications list)
                - GET https://sga.unemi.edu.ec/api/1.0/jwt/alumno/evento?opc_select=2 (Upcoming university events)
                - GET/POST https://sga.unemi.edu.ec/api/1.0/jwt/alumno/tutoria_academica (Academic tutoring requests: action "loadSolicitudes" or "loadMaterias")
                - POST https://sga.unemi.edu.ec/api/1.0/jwt/token/change/academic_period (Switch academic period: body {"refresh":"...", "periodo_id":<id>})
                - POST https://sga.unemi.edu.ec/api/1.0/jwt/token/change/career (Switch career: body {"refresh":"...", "perfil_id":<id>})
                CRITICAL: SGA student endpoints are 100% STATELESS REST APIs. DO NOT claim that SGA requires WebSockets, cookies, a WebView, or browser activation. Pass 'headersJson': '{"Authorization": "Bearer <access_token>"}' when calling JWT endpoints.

                GRADE & STATUS EXTRACTION RULES:
                - Quiz (Cuestionario):
                  * Check 'completiondata.state' in core_course_get_contents. State 0 = incomplete (Pending/No hecho), State > 0 = complete (Submitted/Graded).
                  * If completed, check for 'graderaw' in gradereport_user_get_grade_items. If graderaw is present and not null, status is 'graded' (with grade); if missing or null, status is 'submitted' (Hecho, nota no liberada).
                  * Max grade is 'sumgrades' in mod_quiz_get_quizzes_by_courses, obtained grade is 'graderaw'.
                - Assignment (Tarea):
                  * Check fields in gradereport_user_get_grade_items:
                    + 'gradedatesubmitted' is null -> 'pending' (No entregado)
                    + 'gradedatesubmitted' is not null, 'gradedategraded' is null -> 'submitted' (Entregado, sin calificar)
                    + 'gradedatesubmitted' and 'gradedategraded' are not null -> 'graded' (Calificado), grade is 'graderaw'.
                  * Max grade is 'grademax' or parsed feedback 'gradefordisplay' (after /).
                - Forum (Foro): Max grade from 'grade_forum' in mod_forum_get_forums_by_courses; Obtained grade from 'graderaw' in gradereport_user_get_grade_items.
                - Any item: Max grade is 'grademax', Obtained grade is 'graderaw'.

                Your capabilities:
                - Answer questions about their courses, upcoming deadlines, exam dates, syllabus content, grades, and academic performance across all connected Moodle accounts.
                - Analyze which activities are due soon (sort by closing deadlines across all profiles) and help them prioritize.
                - Calculate grade averages, remaining required points, or show grade statistics.
                - Create or modify custom coded notifications/alarms. The app runs a sandboxed, robust Kotlin-based script interpreter locally. If they ask to set a reminder/alarm (e.g., "Remind me 2 hours before Quiz 1" or "set an alarm always two hours before any closing test"), you must generate a SCHEDULE_NOTIFICATION action with a valid local script in `triggerCode`.
                
                ACTION EXECUTIONS:
                If the user asks you to set an alarm/reminder, you can trigger these actions on the phone. To trigger an action, append a single JSON block inside a markdown code block of type "json" at the VERY END of your response.
                
                To SCHEDULE A NOTIFICATION/ALARM:
                Identify the action parameters. For `triggerCode` custom rules (e.g., event monitoring, grades, activity submissions), always set `timeScheduled` to 0 or current timestamp so monitoring begins immediately. For fixed time reminders without script rules, compute target epoch timestamp. Remember the current year is 2026.
                Use the current local time context provided or calculate appropriate timestamps.
                
                For triggerCode, write a short script that our parser will interpret. It supports:
                - Loop syntax: `for activity in activities { ... }`, `for quiz in quizzes { ... }`, `for assignment in assignments { ... }`, `for course in courses { ... }`
                - Conditionals: `if (condition) { ... } else { ... }`
                - Operators: `==`, `!=`, `<`, `>`, `<=`, `>=`, `&&`, `||`, `!`, `-`, `+`, `*`, `/`
                - Functions:
                  * `notify("title", "body")` for standard notifications
                  * `alarm("title", "body")` for loud ringing alarms with dismissal UI on screen
                  * `hours(h)`, `days(d)`, `minutes(m)` (converts hours/days/minutes to milliseconds)
                - Properties:
                  * `activity.name`, `activity.moduleType`, `activity.dueDate` (milliseconds), `activity.status` ("submitted", "not_submitted", "graded"), `activity.grade`, `activity.maxGrade`
                  * `course.fullName`, `course.shortName`, `course.moodleCourseId`
                  * `now` (current epoch milliseconds)
                
                EXAMPLES OF VALID SCRIPTS:
                1. "always set an alarm two hours before any closing test (quiz)":
                   `for quiz in quizzes { if (quiz.dueDate != null) { val timeLeft = quiz.dueDate - now; if (timeLeft > 0 && timeLeft <= hours(2)) { alarm("Quiz Closing Soon", quiz.name + " closes in less than 2 hours!") } } }`
                2. "notify 5 days before any exam":
                   `for activity in activities { if (activity.moduleType == "quiz" && activity.dueDate != null) { val timeLeft = activity.dueDate - now; if (timeLeft > 0 && timeLeft <= days(5)) { notify("Exam Coming Up", activity.name + " closes in less than 5 days!") } } }`
                3. "set an alarm 1 hour before Assignment 2":
                   `for assignment in assignments { if (assignment.name == "Assignment 2" && assignment.dueDate != null) { val timeLeft = assignment.dueDate - now; if (timeLeft > 0 && timeLeft <= hours(1)) { alarm("Assignment Due Soon", "Assignment 2 closes in 1 hour!") } } }`
                
                Format:
                ```json
                {
                  "action": "schedule_notification",
                  "title": "Rule Name/Title",
                  "body": "Friendly description of what this rule does",
                  "triggerCode": "for quiz in quizzes { if (quiz.dueDate != null) { val timeLeft = quiz.dueDate - now; if (timeLeft > 0 && timeLeft <= hours(2)) { alarm(\"Quiz Closing Soon\", quiz.name + \" closes soon!\") } } }",
                  "ruleType": "ALARM", 
                  "timeScheduled": 1784918200000 
                }
                ```
                Note: `ruleType` can be "ALARM" (loud ringing and overlay) or "NOTIFICATION" (quiet reminder). Choose carefully based on what the user asked for. Use "ALARM" if they say "alarm", or "NOTIFICATION" if they say "notification", "reminder", or "notify".
                
                Keep your main text friendly, informative, encouraging, and formatted in clean Markdown.
                Reference specific course names and deadlines. If they have no grades or activities, advise them to sync their accounts or switch to Demo Mode in Configurations.
            """.trimIndent()

            // Build initial conversation with user's prompt and chat history
            val contentsArray = JSONArray()
            recentHistory.forEach { msg ->
                contentsArray.put(JSONObject().apply {
                    put("role", if (msg.sender == "user") "user" else "model")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", msg.text.take(MAX_HISTORY_CHARS_PER_MSG)) })
                    })
                })
            }
            val userContent = JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", userQuery) })
                })
            }
            contentsArray.put(userContent)

            // Define tools for Function Calling
            val toolsArray = JSONArray()
            val toolObj = JSONObject()
            val funcDeclsArray = JSONArray()
            val funcDeclObj = JSONObject().apply {
                put("name", "executeApiEndpoint")
                put("description", "Execute an HTTP/HTTPS request to any Moodle API endpoint, web service, or external university URL to dynamically fetch or test for needed information (such as grades, course structures, syllabus details, or user data) until the needed data is gathered.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("url", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The full URL of the API endpoint or web resource to test/request")
                        })
                        put("method", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The HTTP method (e.g., GET, POST, PUT, DELETE)")
                        })
                        put("headersJson", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional headers in JSON format, e.g. {\"Authorization\": \"Bearer token\"}")
                        })
                        put("bodyJson", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional request body in JSON or raw format for write requests")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("url")
                        put("method")
                    })
                })
            }
            funcDeclsArray.put(funcDeclObj)
            toolObj.put("functionDeclarations", funcDeclsArray)
            toolsArray.put(toolObj)

            var loopCount = 0
            val maxLoops = 15
            var finalReply = ""

            while (loopCount < maxLoops) {
                // Construct JSON request body for current turn
                val requestJson = JSONObject()
                requestJson.put("contents", contentsArray)

                // System instructions
                val sysInstructionObj = JSONObject()
                val sysPartsArray = JSONArray()
                val sysPartObj = JSONObject()
                sysPartObj.put("text", systemInstructionText)
                sysPartsArray.put(sysPartObj)
                sysInstructionObj.put("parts", sysPartsArray)
                requestJson.put("systemInstruction", sysInstructionObj)

                // Generation config
                val genConfig = JSONObject()
                genConfig.put("temperature", 0.3)
                requestJson.put("generationConfig", genConfig)

                // Supply tools
                requestJson.put("tools", toolsArray)

                Log.d(TAG, "Request payload built (loop ${loopCount + 1}). Sending request to Gemini...")

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = requestJson.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val responseBodyStr = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string() ?: ""
                        Log.e(TAG, "Gemini API failed: ${response.code} - $errBody")
                        return@withContext ChatResult(
                            reply = "Failed to communicate with AI: Server responded with code ${response.code}.\nDetails: $errBody",
                            action = null
                        )
                    }
                    response.body?.string() ?: throw IOException("Empty response from Gemini")
                }

                val responseJson = JSONObject(responseBodyStr)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext ChatResult(
                        reply = "No response candidates returned from the AI.",
                        action = null
                    )
                }

                val candidate = candidates.getJSONObject(0)
                val responseContent = candidate.optJSONObject("content") ?: JSONObject()
                val parts = responseContent.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    return@withContext ChatResult(
                        reply = "AI response contains no parts.",
                        action = null
                    )
                }

                // Check if the AI wants to execute a function
                var hasFunctionCall = false
                var functionCallObj: JSONObject? = null
                var textResponse: String? = null

                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.has("functionCall")) {
                        hasFunctionCall = true
                        functionCallObj = part.getJSONObject("functionCall")
                    }
                    if (part.has("text")) {
                        textResponse = part.getString("text")
                    }
                }

                if (hasFunctionCall && functionCallObj != null) {
                    val name = functionCallObj.getString("name")
                    val args = functionCallObj.optJSONObject("args") ?: JSONObject()

                    Log.i(TAG, "AI requested Function Call: $name with args: $args")

                    val result = if (name == "executeApiEndpoint") {
                        val requestUrl = args.getString("url")
                        val friendlyTask = getFriendlyWsFunction(requestUrl)
                        if (friendlyTask.isNotEmpty()) {
                            onProgress?.invoke("Moodle AI is $friendlyTask...")
                        } else {
                            onProgress?.invoke("Moodle AI is reading endpoints...")
                        }
                        val requestMethod = args.optString("method", "GET")
                        var requestHeaders = args.optString("headersJson", "")
                        val requestBodyStr = args.optString("bodyJson", "")

                        // Auto-inject / overwrite active SGA Bearer token if querying SGA endpoints
                        if ((requestUrl.contains("sga.unemi.edu.ec") || requestUrl.contains("sgaestudiante.unemi.edu.ec")) && currentSgaAccessToken.isNotEmpty()) {
                            try {
                                val hObj = if (requestHeaders.isNotBlank()) JSONObject(requestHeaders) else JSONObject()
                                hObj.put("Authorization", "Bearer $currentSgaAccessToken")
                                requestHeaders = hObj.toString()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error updating SGA token header", e)
                            }
                        }

                        performHttpRequest(
                            url = requestUrl,
                            method = requestMethod,
                            headersJson = requestHeaders,
                            bodyJson = requestBodyStr,
                            sgaAccessTokenProvider = { currentSgaAccessToken },
                            sgaRefreshTokenProvider = { currentSgaRefreshToken },
                            sgaUserProvider = { sgaUser },
                            sgaPassProvider = { sgaPass },
                            onSgaRefreshed = { newAccess, newRefresh ->
                                currentSgaAccessToken = newAccess
                                currentSgaRefreshToken = newRefresh
                                onSgaTokenRefreshed?.invoke(newAccess, newRefresh)
                            }
                        )
                    } else {
                        "Unknown function name: $name"
                    }

                    Log.d(TAG, "Function response result: $result")

                    // Add model's turn with the functionCall to history
                    contentsArray.put(responseContent)

                    // Add the function's response turn to history
                    val functionResponseTurn = JSONObject().apply {
                        put("role", "function")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("functionResponse", JSONObject().apply {
                                    put("name", name)
                                    put("response", JSONObject().apply {
                                        put("output", result)
                                    })
                                })
                            })
                        })
                    }
                    contentsArray.put(functionResponseTurn)
                    loopCount++
                } else {
                    // No function call, we have reached the final reply!
                    finalReply = textResponse ?: ""
                    break
                }
            }

            if (finalReply.isEmpty() && loopCount >= maxLoops) {
                try {
                    onProgress?.invoke("Moodle AI is summarizing findings...")
                    val explanationContentsArray = JSONArray()
                    for (i in 0 until contentsArray.length()) {
                        explanationContentsArray.put(contentsArray.getJSONObject(i))
                    }
                    explanationContentsArray.put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "CRITICAL: You have executed 15 Moodle API requests and reached the maximum execution step limit of this turn. Please summarize what endpoints you checked, what you discovered, and explain to the user that you hit the limit but they can say 'continue' or write another message if they want you to keep searching or complete the task.")
                            })
                        })
                    })

                    val requestJson = JSONObject()
                    requestJson.put("contents", explanationContentsArray)

                    val sysInstructionObj = JSONObject()
                    val sysPartsArray = JSONArray()
                    val sysPartObj = JSONObject()
                    sysPartObj.put("text", systemInstructionText)
                    sysPartsArray.put(sysPartObj)
                    sysInstructionObj.put("parts", sysPartsArray)
                    requestJson.put("systemInstruction", sysInstructionObj)

                    val genConfig = JSONObject()
                    genConfig.put("temperature", 0.5)
                    requestJson.put("generationConfig", genConfig)

                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val requestBody = requestJson.toString().toRequestBody(mediaType)
                    val request = Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build()

                    val responseBodyStr = client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.string() ?: ""
                        } else ""
                    }
                    if (responseBodyStr.isNotEmpty()) {
                        val responseJson = JSONObject(responseBodyStr)
                        val candidates = responseJson.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val candidate = candidates.getJSONObject(0)
                            val responseContent = candidate.optJSONObject("content") ?: JSONObject()
                            val parts = responseContent.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val firstPart = parts.getJSONObject(0)
                                if (firstPart.has("text")) {
                                    finalReply = firstPart.getString("text")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get Gemini final loop explanation", e)
                }

                if (finalReply.isEmpty()) {
                    finalReply = "I have queried the university platform 15 times to fetch your information, but reached the execution step limit before finishing. Please write a message like 'continue' so I can keep searching and complete your request!"
                }
            }

            // Parse potential embedded action block in the final reply
            val parsedResult = extractActionBlock(finalReply)
            return@withContext parsedResult

        } catch (e: Exception) {
            Log.e(TAG, "Error chatting with Gemini", e)
            return@withContext ChatResult(
                reply = "Error: Could not connect to the AI model. Details: ${e.localizedMessage ?: e.message}. Check your internet connection or API Key.",
                action = null
            )
        }
    }

    /**
     * Extracts the profile/period/matricula ids from the stored SGA JWT payload so
     * the model always works with the CURRENT user's real ids (never hardcoded).
     */
    private fun buildSgaSessionContext(sgaSessionPayload: String): String {
        if (sgaSessionPayload.isBlank()) {
            return "(No SGA session payload stored — user must log in to SGA in Configurations before switching careers or periods.)"
        }
        return try {
            val json = JSONObject(sgaSessionPayload)
            val parts = mutableListOf<String>()
            json.optJSONObject("perfilprincipal")?.let { parts.add("active_profile: ${it.toString()}") }
            json.optJSONArray("perfiles")?.let { parts.add("all_profiles (use 'id' as perfil_id): ${it.toString()}") }
            json.optJSONArray("periodos")?.let { parts.add("all_periods (use 'id' as periodo_id): ${it.toString()}") }
            json.optJSONObject("periodo")?.let { parts.add("current_period: ${it.toString()}") }
            json.optJSONObject("matricula")?.let { parts.add("matricula: ${it.toString()}") }
            json.optJSONObject("inscripcion")?.let { parts.add("inscripcion: ${it.toString()}") }
            if (parts.isEmpty()) sgaSessionPayload.take(2000) else parts.joinToString("\n")
        } catch (e: Exception) {
            sgaSessionPayload.take(2000)
        }
    }

    /**
     * SGA token-rotation endpoints (token/change/career, token/change/academic_period,
     * token/refresh) return a fresh {access, refresh} pair in the response body.
     * Returns it so the app keeps using the new session after e.g. a career switch.
     */
    private fun extractSgaRotatedTokens(url: String, body: String): Pair<String, String>? {
        if (!url.contains("sga.unemi.edu.ec") || !url.contains("/token/")) return null
        return try {
            val json = JSONObject(body)
            val access = json.optString("access", "")
            val refresh = json.optString("refresh", "")
            if (access.isNotEmpty() && refresh.isNotEmpty()) access to refresh else null
        } catch (e: Exception) {
            null
        }
    }

    private fun getFriendlyWsFunction(url: String): String {
        if (url.contains("sga.unemi.edu.ec") || url.contains("sgaestudiante.unemi.edu.ec")) {
            return when {
                url.contains("alumno/notas") -> "fetching UNEMI academic record & grades"
                url.contains("alumno/malla") -> "fetching UNEMI curriculum & malla"
                url.contains("alumno/materias") -> "fetching UNEMI current subjects"
                url.contains("alumno/horarioexamen") -> "fetching UNEMI exam schedule"
                url.contains("alumno/horario") -> "fetching UNEMI class timetable"
                url.contains("alumno/asistencia") -> "fetching UNEMI attendance details"
                url.contains("alumno/finanzas") -> "fetching UNEMI financial records & rubros"
                url.contains("alumno/general/data") -> "fetching UNEMI calendar & student data"
                url.contains("alumno/panel") -> "fetching UNEMI student dashboard modules"
                url.contains("alumno/notificacion") -> "fetching UNEMI notifications"
                url.contains("alumno/evento") -> "fetching UNEMI events"
                url.contains("alumno/tutoria_academica") -> "fetching UNEMI academic tutoring"
                url.contains("api/auth/login.json") -> "authenticating with UNEMI SGA"
                url.contains("token/refresh") -> "refreshing UNEMI session token"
                else -> "querying UNEMI SGA service"
            }
        }
        val regex = "wsfunction=([^&]+)".toRegex()
        val match = regex.find(url)
        val func = match?.groupValues?.get(1) ?: return ""
        return when (func) {
            "core_enrol_get_enrolled_users" -> "fetching group members / enrolled users"
            "gradereport_user_get_grade_items" -> "fetching course grades"
            "mod_quiz_get_quizzes_by_courses" -> "fetching quiz list and deadlines"
            "mod_assign_get_assignments" -> "fetching assignment list and deadlines"
            "core_course_get_contents" -> "fetching subject contents & topics"
            "core_group_get_course_user_groups" -> "fetching user group memberships"
            "core_course_search_courses" -> "searching courses in university platform"
            else -> "executing Moodle task ($func)"
        }
    }

    /**
     * Executes a physical network request for dynamic endpoint testing by the AI.
     */
    private suspend fun performHttpRequest(
        url: String,
        method: String,
        headersJson: String,
        bodyJson: String,
        sgaAccessTokenProvider: () -> String = { "" },
        sgaRefreshTokenProvider: () -> String = { "" },
        sgaUserProvider: () -> String = { "" },
        sgaPassProvider: () -> String = { "" },
        onSgaRefreshed: ((newAccess: String, newRefresh: String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(url)

            if (headersJson.isNotEmpty()) {
                try {
                    val headersObj = JSONObject(headersJson)
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        builder.addHeader(key, headersObj.getString(key))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse headersJson for dynamic request", e)
                }
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = when (method.uppercase()) {
                "GET" -> builder.get().build()
                "POST" -> {
                    val requestBody = bodyJson.toRequestBody(mediaType)
                    builder.post(requestBody).build()
                }
                "PUT" -> {
                    val requestBody = bodyJson.toRequestBody(mediaType)
                    builder.put(requestBody).build()
                }
                "DELETE" -> {
                    if (bodyJson.isNotEmpty()) {
                        builder.delete(bodyJson.toRequestBody(mediaType)).build()
                    } else {
                        builder.delete().build()
                    }
                }
                else -> builder.get().build()
            }

            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""

                // SGA career/period/refresh endpoints rotate the JWT pair in the response
                // body — capture the new tokens so subsequent calls use the new session.
                extractSgaRotatedTokens(url, body)?.let { (newAccess, newRefresh) ->
                    Log.i(TAG, "SGA session rotated by endpoint: $url — capturing new tokens")
                    onSgaRefreshed?.invoke(newAccess, newRefresh)
                }

                val curRefreshToken = sgaRefreshTokenProvider()
                val curUser = sgaUserProvider()
                val curPass = sgaPassProvider()

                // Handle SGA token expiration (401/403) by attempting automatic token refresh & silent re-login fallback
                if ((code == 401 || code == 403) && url.contains("sga.unemi.edu.ec")) {
                    // The Authorization token that was actually sent with this request.
                    // Compared against the current token inside the lock to detect that
                    // a concurrent tool call already renewed the session.
                    val sentAccessToken = try {
                        if (headersJson.isNotBlank()) {
                            JSONObject(headersJson).optString("Authorization", "")
                                .removePrefix("Bearer ").trim()
                        } else ""
                    } catch (e: Exception) {
                        ""
                    }

                    var autoRetryResult: String? = null
                    sgaRefreshMutex.withLock {
                        val currentAccess = sgaAccessTokenProvider()
                        val alreadyRefreshed = currentAccess.isNotEmpty() && currentAccess != sentAccessToken

                        if (alreadyRefreshed) {
                            // A parallel tool call hit the 401 first and renewed the
                            // session while we were waiting — just retry with the fresh token.
                            Log.i(TAG, "SGA session already renewed by a concurrent request — retrying with fresh token")
                        } else {
                            // Step 1: Attempt Refresh Token
                            var newAccess = ""
                            var newRefresh = ""
                            if (curRefreshToken.isNotEmpty()) {
                                try {
                                    Log.i(TAG, "SGA endpoint returned $code. Attempting automatic token refresh...")
                                    val newAuth = SgaApiClient.refreshToken(curRefreshToken)
                                    newAccess = newAuth.accessToken
                                    newRefresh = newAuth.refreshToken
                                } catch (e: Exception) {
                                    Log.e(TAG, "SGA Auto-refresh failed: ${e.message}. Trying silent re-login...")
                                }
                            }

                            // Step 2: If Refresh Token failed or was invalid, attempt silent re-login with stored credentials
                            if (newAccess.isEmpty() && curUser.isNotEmpty() && curPass.isNotEmpty()) {
                                try {
                                    Log.i(TAG, "Attempting silent SGA re-login for user '$curUser'...")
                                    val newAuth = SgaApiClient.login(curUser, curPass)
                                    newAccess = newAuth.accessToken
                                    newRefresh = newAuth.refreshToken
                                } catch (e: Exception) {
                                    Log.e(TAG, "SGA Silent re-login failed: ${e.message}")
                                }
                            }

                            if (newAccess.isNotEmpty()) {
                                onSgaRefreshed?.invoke(newAccess, newRefresh)
                            }
                        }

                        // Retry with the newest token: either freshly obtained above or
                        // already installed by the concurrent refresh.
                        val freshAccess = sgaAccessTokenProvider()
                        if (freshAccess.isNotEmpty()) {
                            val newHeadersObj = if (headersJson.isNotBlank()) JSONObject(headersJson) else JSONObject()
                            newHeadersObj.put("Authorization", "Bearer $freshAccess")

                            val retryBuilder = Request.Builder().url(url)
                            val keys = newHeadersObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                retryBuilder.addHeader(k, newHeadersObj.getString(k))
                            }

                            val retryRequest = when (method.uppercase()) {
                                "POST" -> retryBuilder.post(bodyJson.toRequestBody(mediaType)).build()
                                "PUT" -> retryBuilder.put(bodyJson.toRequestBody(mediaType)).build()
                                "DELETE" -> if (bodyJson.isNotEmpty()) retryBuilder.delete(bodyJson.toRequestBody(mediaType)).build() else retryBuilder.delete().build()
                                else -> retryBuilder.get().build()
                            }

                            client.newCall(retryRequest).execute().use { retryResponse ->
                                val retryCode = retryResponse.code
                                val retryBody = retryResponse.body?.string() ?: ""

                                // The retried endpoint may itself rotate the JWT pair (e.g. a
                                // token/change call that 401'd) — capture it if present.
                                extractSgaRotatedTokens(url, retryBody)?.let { (rotAccess, rotRefresh) ->
                                    onSgaRefreshed?.invoke(rotAccess, rotRefresh)
                                }

                                val headersMap = retryResponse.headers.toMultimap()
                                val headersJsonObj = JSONObject()
                                headersMap.forEach { (k, v) ->
                                    headersJsonObj.put(k, JSONArray(v))
                                }

                                autoRetryResult = JSONObject().apply {
                                    put("status", retryCode)
                                    put("isSuccessful", retryResponse.isSuccessful)
                                    put("headers", headersJsonObj)
                                    put("body", retryBody)
                                    put("autoRefreshedToken", true)
                                }.toString()
                            }
                        }
                    }

                    if (autoRetryResult != null) {
                        return@withContext autoRetryResult
                    }
                }

                val headersMap = response.headers.toMultimap()
                val headersJsonObj = JSONObject()
                headersMap.forEach { (k, v) ->
                    headersJsonObj.put(k, JSONArray(v))
                }

                JSONObject().apply {
                    put("status", code)
                    put("isSuccessful", response.isSuccessful)
                    put("headers", headersJsonObj)
                    put("body", body)
                }.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dynamic HTTP execution error", e)
            JSONObject().apply {
                put("status", 0)
                put("isSuccessful", false)
                put("error", e.localizedMessage ?: e.message ?: "Network unreachable or timeout")
            }.toString()
        }
    }

    /**
     * Extracts a markdown json block from the text and parses it into an AiAction.
     */
    private fun extractActionBlock(text: String): ChatResult {
        var cleanText = text
        var action: AiAction? = null

        try {
            // Find ```json or ```JSON code block
            val startIdx = text.indexOf("```json")
            val endIdx = if (startIdx != -1) text.indexOf("```", startIdx + 7) else -1

            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                val jsonString = text.substring(startIdx + 7, endIdx).trim()
                // Strip the JSON block from user-visible reply to keep the conversation clean
                cleanText = text.replaceRange(startIdx, endIdx + 3, "").trim()
                
                val json = JSONObject(jsonString)
                val actionName = json.getString("action")
                
                if (actionName == "schedule_notification") {
                    val rType = json.optString("ruleType", json.optString("type", "NOTIFICATION")).uppercase()
                    action = AiAction.ScheduleNotification(
                        title = json.getString("title"),
                        body = json.getString("body"),
                        triggerCode = json.getString("triggerCode"),
                        timeScheduled = json.getLong("timeScheduled"),
                        ruleType = if (rType == "ALARM") "ALARM" else "NOTIFICATION"
                    )
                } else if (actionName == "delete_notification") {
                    action = AiAction.DeleteNotification(
                        id = json.getInt("id")
                    )
                } else if (actionName == "edit_notification") {
                    val rType = json.optString("ruleType", json.optString("type", "NOTIFICATION")).uppercase()
                    action = AiAction.EditNotification(
                        id = json.getInt("id"),
                        title = json.optString("title", ""),
                        body = json.optString("body", ""),
                        triggerCode = json.optString("triggerCode", ""),
                        timeScheduled = json.optLong("timeScheduled", 0L),
                        ruleType = if (rType == "ALARM") "ALARM" else "NOTIFICATION"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse embedded AI action JSON", e)
        }

        return ChatResult(reply = cleanText, action = action)
    }

    data class ChatResult(
        val reply: String,
        val action: AiAction?
    )

    sealed interface AiAction {
        data class ScheduleNotification(
            val title: String,
            val body: String,
            val triggerCode: String,
            val timeScheduled: Long,
            val ruleType: String
        ) : AiAction

        data class DeleteNotification(
            val id: Int
        ) : AiAction

        data class EditNotification(
            val id: Int,
            val title: String,
            val body: String,
            val triggerCode: String,
            val timeScheduled: Long,
            val ruleType: String
        ) : AiAction
    }
}
