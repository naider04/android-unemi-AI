package com.example.data.network

import android.util.Log
import com.example.data.db.ActivityItem
import com.example.data.db.Course
import com.example.data.db.MoodleAccount
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GradeReportDetail(
    val gradeRaw: String?,
    val maxGrade: String,
    val gradedatesubmitted: Long?,
    val gradedategraded: Long?
)

object MoodleApiClient {
    private const val TAG = "MoodleApiClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Attempts to fetch a mobile web service token from the Moodle site.
     */
    suspend fun fetchToken(baseUrl: String, username: String, password: String): String {
        val sanitizedUrl = sanitizeUrl(baseUrl)
        val url = "$sanitizedUrl/login/token.php?username=${java.net.URLEncoder.encode(username, "UTF-8")}&password=${java.net.URLEncoder.encode(password, "UTF-8")}&service=moodle_mobile_app"
        
        Log.d(TAG, "Fetching token from: $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Network error: ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response from Moodle")
            val json = JSONObject(body)
            if (json.has("error")) {
                throw IOException(json.optString("error", "Unknown Moodle error"))
            }
            if (json.has("token")) {
                return json.getString("token")
            }
            throw IOException("Token not found in response. Verify service configuration.")
        }
    }

    /**
     * Calls core_webservice_get_site_info to get user details.
     */
    suspend fun fetchSiteInfo(baseUrl: String, token: String): SiteInfo {
        val sanitizedUrl = sanitizeUrl(baseUrl)
        val url = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=core_webservice_get_site_info&moodlewsrestformat=json"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Network error: ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response")
            val json = JSONObject(body)
            if (json.has("exception")) {
                throw IOException(json.optString("message", "Moodle exception"))
            }
            val userId = json.getInt("userid")
            val fullname = json.optString("fullname", "Moodle Student")
            val avatarUrl = json.optString("userpictureurl", null)
            return SiteInfo(userId = userId, fullname = fullname, avatarUrl = avatarUrl)
        }
    }

    /**
     * Fallback to fetch courses via overview report if core_enrol_get_users_courses fails.
     */
    private suspend fun fetchCoursesFallback(baseUrl: String, token: String, accountId: Int): List<Course> {
        val sanitizedUrl = sanitizeUrl(baseUrl)
        val url = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=gradereport_overview_get_course_grades&moodlewsrestformat=json"
        
        Log.d(TAG, "Attempting fetchCoursesFallback: $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val gradesArray = json.optJSONArray("grades") ?: return emptyList()
                
                val coursesList = mutableListOf<Course>()
                for (i in 0 until gradesArray.length()) {
                    val gradeObj = gradesArray.getJSONObject(i)
                    val courseId = gradeObj.optInt("courseid", -1)
                    if (courseId != -1) {
                        // Attempt to fetch course details using core_course_get_courses_by_field
                        var fullName = "Course $courseId"
                        var shortName = "C-$courseId"
                        
                        try {
                            val detailsUrl = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=core_course_get_courses_by_field&moodlewsrestformat=json&field=id&value=$courseId"
                            val detailsRequest = Request.Builder().url(detailsUrl).get().build()
                            client.newCall(detailsRequest).execute().use { detailsResponse ->
                                if (detailsResponse.isSuccessful) {
                                    val detailsBody = detailsResponse.body?.string()
                                    if (detailsBody != null) {
                                        val detailsJson = JSONObject(detailsBody)
                                        val coursesArray = detailsJson.optJSONArray("courses")
                                        if (coursesArray != null && coursesArray.length() > 0) {
                                            val courseObj = coursesArray.getJSONObject(0)
                                            fullName = courseObj.optString("fullname", fullName)
                                            shortName = courseObj.optString("shortname", shortName)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to get details for course $courseId", e)
                        }
                        
                        coursesList.add(
                            Course(
                                accountId = accountId,
                                moodleCourseId = courseId,
                                fullName = fullName,
                                shortName = shortName
                            )
                        )
                    }
                }
                return coursesList
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchCoursesFallback", e)
            return emptyList()
        }
    }

    /**
     * Gets courses for the user.
     */
    suspend fun fetchCourses(baseUrl: String, token: String, userId: Int, accountId: Int): List<Course> {
        val sanitizedUrl = sanitizeUrl(baseUrl)
        val url = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=core_enrol_get_users_courses&moodlewsrestformat=json&userid=$userId"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val courses = mutableListOf<Course>()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null && body.trim().startsWith("[")) {
                        val array = JSONArray(body)
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            courses.add(
                                Course(
                                    accountId = accountId,
                                    moodleCourseId = obj.getInt("id"),
                                    fullName = obj.getString("fullname"),
                                    shortName = obj.optString("shortname", "")
                                )
                            )
                        }
                    } else if (body != null) {
                        Log.w(TAG, "fetchCourses returned non-array payload: $body")
                    }
                } else {
                    Log.w(TAG, "fetchCourses failed with response code ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calling core_enrol_get_users_courses", e)
        }

        if (courses.isEmpty()) {
            Log.d(TAG, "No courses found from core_enrol_get_users_courses, attempting fallback...")
            courses.addAll(fetchCoursesFallback(baseUrl, token, accountId))
        }

        return courses
    }

    /**
     * Fetches contents (activities) of a course and merges with grades if available.
     */
    suspend fun fetchCourseActivities(
        baseUrl: String,
        token: String,
        courseId: Int,
        accountId: Int,
        userId: Int
    ): List<ActivityItem> {
        val sanitizedUrl = sanitizeUrl(baseUrl)
        
        // 1. Fetch Contents
        val contentsUrl = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=core_course_get_contents&moodlewsrestformat=json&courseid=$courseId"
        val requestContents = Request.Builder().url(contentsUrl).get().build()
        
        val activities = mutableListOf<ActivityItem>()
        val gradesMap = mutableMapOf<String, Pair<String, String>>() // Key: "moduleType-instanceId" -> Pair(grade, maxGrade)
        val gradesDetailMap = mutableMapOf<String, GradeReportDetail>()
        val gradeItemsList = mutableListOf<JSONObject>()

        // 2. Fetch Grades (gradereport_user_get_grade_items) with userid
        try {
            val gradesUrl = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=gradereport_user_get_grade_items&moodlewsrestformat=json&courseid=$courseId&userid=$userId"
            val requestGrades = Request.Builder().url(gradesUrl).get().build()
            client.newCall(requestGrades).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val json = JSONObject(body)
                        val usergrades = json.optJSONArray("usergrades")
                        if (usergrades != null && usergrades.length() > 0) {
                            val gradeItems = usergrades.getJSONObject(0).optJSONArray("gradeitems")
                            if (gradeItems != null) {
                                for (i in 0 until gradeItems.length()) {
                                    val item = gradeItems.getJSONObject(i)
                                    if (item.optString("itemtype", "") == "mod") {
                                        gradeItemsList.add(item)
                                    }
                                    
                                    val itemType = item.optString("itemmodule", "").ifEmpty { item.optString("activitymodule", "") }
                                    val itemInstance = item.optInt("iteminstance", -1)
                                    val gradeRaw = item.optString("graderaw", "")
                                    val gradeRawStr = if (gradeRaw.isNotEmpty() && gradeRaw != "null") gradeRaw else stripHtml(item.optString("gradeformatted", "-"))
                                    val maxGrade = item.optString("grademax", "100")
                                    
                                    val gradedatesubmitted = if (item.isNull("gradedatesubmitted")) null else item.optLong("gradedatesubmitted", 0L).let { if (it <= 0L) null else it }
                                    val gradedategraded = if (item.isNull("gradedategraded")) null else item.optLong("gradedategraded", 0L).let { if (it <= 0L) null else it }
                                    val parsedRawGrade = if (gradeRawStr.isNotEmpty() && gradeRawStr != "-") gradeRawStr else null

                                    if (itemType.isNotEmpty() && itemInstance != -1) {
                                        gradesMap["$itemType-$itemInstance"] = Pair(gradeRawStr, maxGrade)
                                        gradesDetailMap["$itemType-$itemInstance"] = GradeReportDetail(
                                            gradeRaw = parsedRawGrade,
                                            maxGrade = maxGrade,
                                            gradedatesubmitted = gradedatesubmitted,
                                            gradedategraded = gradedategraded
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not fetch grades for course $courseId", e)
        }

        // 3. Fetch Quizzes and Assignments metadata lists for the course
        val quizzesMap = mutableMapOf<Int, JSONObject>()
        try {
            val quizzes = fetchQuizzesByCourse(baseUrl, token, courseId)
            quizzes.forEach { q ->
                val qId = q.optInt("id", -1)
                if (qId != -1) quizzesMap[qId] = q
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pre-fetching quizzes", e)
        }

        val assignmentsMap = mutableMapOf<Int, JSONObject>()
        try {
            val assignments = fetchAssignmentsByCourse(baseUrl, token, courseId)
            assignments.forEach { a ->
                val aId = a.optInt("id", -1)
                if (aId != -1) assignmentsMap[aId] = a
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pre-fetching assignments", e)
        }

        val forumsMap = mutableMapOf<Int, JSONObject>()
        try {
            val forums = fetchForumsByCourse(baseUrl, token, courseId)
            forums.forEach { f ->
                val fId = f.optInt("id", -1)
                if (fId != -1) forumsMap[fId] = f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pre-fetching forums", e)
        }

        // 4. Fetch Contents and parse modules
        try {
            client.newCall(requestContents).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return emptyList()
                    val sections = JSONArray(body)
                    for (s in 0 until sections.length()) {
                        val section = sections.getJSONObject(s)
                        val modules = section.optJSONArray("modules") ?: continue
                        for (m in 0 until modules.length()) {
                            val mod = modules.getJSONObject(m)
                            val modName = mod.getString("name")
                            val modType = mod.getString("modname") // "assign", "quiz", etc.
                            val modId = mod.getInt("id") // module ID (cmid)
                            val instanceId = mod.optInt("instance", -1)
                            
                            if (!isGradable(mod, forumsMap)) {
                                continue
                            }
                            
                            // Try to get due date from section metadata
                            val dates = mod.optJSONArray("dates")
                            var dueDate: Long? = null
                            if (dates != null) {
                                for (d in 0 until dates.length()) {
                                    val dateObj = dates.getJSONObject(d)
                                    if (dateObj.optString("label", "").contains("Due", ignoreCase = true) ||
                                        dateObj.optString("label", "").contains("Cierre", ignoreCase = true) ||
                                        dateObj.optString("label", "").contains("Expected", ignoreCase = true) ||
                                        dateObj.optString("label", "").contains("entrega", ignoreCase = true)) {
                                        dueDate = dateObj.optLong("timestamp", 0L)
                                        if (dueDate == 0L) dueDate = null
                                    }
                                }
                            }
                            
                            // Fallback due dates from assignment/quiz metadata lists
                            if (dueDate == null) {
                                if (modType == "assign") {
                                    val assignMeta = assignmentsMap[instanceId]
                                    if (assignMeta != null) {
                                        val dd = assignMeta.optLong("duedate", 0L)
                                        if (dd > 0) dueDate = dd
                                    }
                                } else if (modType == "quiz") {
                                    val quizMeta = quizzesMap[instanceId]
                                    if (quizMeta != null) {
                                        val tc = quizMeta.optLong("timeclose", 0L)
                                        if (tc > 0) dueDate = tc
                                    }
                                } else if (modType == "forum") {
                                    val forumMeta = forumsMap[instanceId]
                                    if (forumMeta != null) {
                                        val dd = forumMeta.optLong("duedate", 0L)
                                        if (dd > 0) dueDate = dd
                                    }
                                }
                            }

                            // Dynamic Grade Extraction & Submission Status Checking
                            var status = "pending"
                            var grade: String? = null
                            var maxGrade: String? = "10"

                            if (modType == "quiz" && instanceId != -1) {
                                val quizMeta = quizzesMap[instanceId]
                                val sg = quizMeta?.optDouble("sumgrades", -1.0) ?: -1.0
                                val g = quizMeta?.optDouble("grade", -1.0) ?: -1.0
                                maxGrade = if (sg > 0) sg.toString() else if (g > 0) g.toString() else {
                                    gradesMap["$modType-$instanceId"]?.second ?: "10"
                                }
                                
                                val completionData = mod.optJSONObject("completiondata")
                                val completionState = completionData?.optInt("state", 0) ?: 0
                                
                                val reportDetail = gradesDetailMap["$modType-$instanceId"]
                                val reportRawGrade = reportDetail?.gradeRaw

                                if (reportRawGrade != null && reportRawGrade.isNotEmpty() && reportRawGrade != "null") {
                                    status = "graded"
                                    grade = reportRawGrade
                                } else {
                                    val completionData = mod.optJSONObject("completiondata")
                                    val completionState = completionData?.optInt("state", 0) ?: 0
                                    
                                    if (completionData != null && completionState > 0) {
                                        status = "submitted"
                                        grade = null
                                    } else {
                                        // Try attempts as second fallback
                                        try {
                                            val attempts = fetchQuizUserAttempts(baseUrl, token, instanceId, userId)
                                            val lastAttempt = attempts.sortedBy { it.optInt("attempt", 1) }.lastOrNull()
                                            if (lastAttempt != null) {
                                                val isSubmittedNull = lastAttempt.isNull("gradedatesubmitted") || lastAttempt.optLong("gradedatesubmitted", 0L) == 0L
                                                val isGradedNull = lastAttempt.isNull("gradedategraded") || lastAttempt.optLong("gradedategraded", 0L) == 0L
                                                
                                                if (isSubmittedNull) {
                                                    status = "pending"
                                                    grade = null
                                                } else if (isGradedNull) {
                                                    status = "submitted"
                                                    grade = null
                                                 } else {
                                                    status = "graded"
                                                    val attemptGrade = lastAttempt.optDouble("sumgrades", -1.0)
                                                    grade = if (attemptGrade >= 0.0) attemptGrade.toString() else null
                                                }
                                            } else {
                                                status = "pending"
                                                grade = null
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Fallback quiz attempt check failed for quiz $instanceId", e)
                                            status = "pending"
                                            grade = null
                                        }
                                    }
                                }

                            } else if (modType == "assign" && instanceId != -1) {
                                val assignMeta = assignmentsMap[instanceId]
                                val assignGrade = assignMeta?.optString("grade", "") ?: ""
                                maxGrade = if (assignGrade.isNotEmpty() && assignGrade != "null" && assignGrade != "0") {
                                    assignGrade
                                } else {
                                    gradesMap["$modType-$instanceId"]?.second ?: "10"
                                }

                                val reportDetail = gradesDetailMap["$modType-$instanceId"]
                                if (reportDetail != null) {
                                    val submittedTs = reportDetail.gradedatesubmitted
                                    val gradedTs = reportDetail.gradedategraded
                                    val rawGrade = reportDetail.gradeRaw
                                    
                                    if (rawGrade != null && rawGrade.isNotEmpty() && rawGrade != "null") {
                                        status = "graded"
                                        grade = rawGrade
                                    } else if (gradedTs != null) {
                                        status = "graded"
                                        grade = rawGrade ?: "0"
                                    } else if (submittedTs == null) {
                                        status = "pending"
                                        grade = null
                                    } else if (gradedTs == null) {
                                        status = "submitted"
                                        grade = null
                                    } else {
                                        status = "graded"
                                        grade = rawGrade ?: "0"
                                    }
                                } else {
                                    // Fallback: use individual assignment status api
                                    try {
                                        val subStatus = fetchAssignmentSubmissionStatus(baseUrl, token, instanceId, userId)
                                        if (subStatus != null) {
                                            val lastAttempt = subStatus.optJSONObject("lastattempt")
                                            if (lastAttempt != null) {
                                                val submission = lastAttempt.optJSONObject("submission")
                                                val subState = submission?.optString("status", "") ?: ""
                                                val gradingstatus = lastAttempt.optString("gradingstatus", "")
                                                
                                                if (subState == "submitted") {
                                                    status = "submitted"
                                                }
                                                if (gradingstatus == "graded") {
                                                    status = "graded"
                                                }
                                            }
                                            
                                            val feedback = subStatus.optJSONObject("feedback")
                                            val gradeForDisplay = feedback?.optString("gradefordisplay", "") ?: ""
                                            if (gradeForDisplay.isNotEmpty() && gradeForDisplay.contains("/")) {
                                                val parts = gradeForDisplay.split("/")
                                                if (parts.size == 2) {
                                                    val obtainedStr = parts[0].trim()
                                                    val maxStr = parts[1].trim()
                                                    grade = obtainedStr
                                                    maxGrade = maxStr
                                                    status = "graded"
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error fetching submission status fallback for assign $instanceId", e)
                                        status = "pending"
                                        grade = null
                                    }
                                }
                            } else if (modType == "forum" && instanceId != -1) {
                                val forumMeta = forumsMap[instanceId]
                                val gradeForum = forumMeta?.optString("grade_forum", "")?.let {
                                    if (it.isEmpty() || it == "null") forumMeta.optString("grade", "") else it
                                } ?: ""
                                maxGrade = if (gradeForum.isNotEmpty() && gradeForum != "null" && gradeForum != "0") {
                                    gradeForum
                                } else {
                                    gradesMap["$modType-$instanceId"]?.second ?: "10"
                                }

                                val reportDetail = gradesDetailMap["$modType-$instanceId"]
                                val reportRawGrade = reportDetail?.gradeRaw
                                if (reportRawGrade != null && reportRawGrade.isNotEmpty() && reportRawGrade != "null") {
                                    status = "graded"
                                    grade = reportRawGrade
                                } else {
                                    val completionData = mod.optJSONObject("completiondata")
                                    val completionState = completionData?.optInt("state", 0) ?: 0
                                    if (completionData != null && completionState > 0) {
                                        status = "submitted"
                                        grade = null
                                    } else {
                                        status = "pending"
                                        grade = null
                                    }
                                }
                            } else {
                                maxGrade = gradesMap["$modType-$instanceId"]?.second ?: "10"
                                val reportDetail = gradesDetailMap["$modType-$instanceId"]
                                val reportRawGrade = reportDetail?.gradeRaw
                                if (reportRawGrade != null && reportRawGrade.isNotEmpty() && reportRawGrade != "null") {
                                    status = "graded"
                                    grade = reportRawGrade
                                }
                            }

                            val now = System.currentTimeMillis() / 1000L
                            var finalStatus = status
                            var finalGrade = grade
                            if ((finalStatus == "pending" || finalStatus == "not_submitted") && dueDate != null && now > dueDate) {
                                finalStatus = "not_submitted"
                                finalGrade = "0"
                            }

                            activities.add(
                                ActivityItem(
                                    accountId = accountId,
                                    courseId = courseId,
                                    moodleActivityId = modId,
                                    name = modName,
                                    moduleType = modType,
                                    dueDate = dueDate,
                                    status = finalStatus,
                                    grade = formatGrade(finalGrade),
                                    maxGrade = formatMaxGrade(maxGrade)
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching contents for course $courseId", e)
        }

        // 5. Fill gaps from gradebook items to ensure ALL grades are shown
        val existingActivityIds = activities.map { it.moodleActivityId }.toSet()
        for (item in gradeItemsList) {
            val activityId = item.optInt("activityid", -1).let { if (it == -1) item.optInt("cmid", -1) else it }
            if (activityId != -1 && !existingActivityIds.contains(activityId)) {
                val itemType = item.optString("itemmodule", "").ifEmpty { item.optString("activitymodule", "assign") }
                val itemInstance = item.optInt("iteminstance", -1)
                
                val rawGrade = if (item.isNull("graderaw")) null else {
                    val r = item.optString("graderaw", "")
                    if (r == "null" || r.isEmpty()) null else r
                }
                
                val displayGrade = rawGrade ?: if (item.isNull("gradeformatted")) null else {
                    val f = stripHtml(item.optString("gradeformatted", ""))
                    if (f == "null" || f.isEmpty() || f == "-") null else f
                }
                
                val maxGrade = item.optString("grademax", "10")
                val activityName = item.optString("activityname", "").ifEmpty { item.optString("itemname", "Activity") }
                
                var status = "pending"
                if (displayGrade != null) {
                    status = "graded"
                } else if (!item.isNull("gradedatesubmitted") && item.optLong("gradedatesubmitted", 0L) > 0L) {
                    status = "submitted"
                }
                
                // Try to resolve due date from quizzes or assignments list if available
                var dueDate: Long? = null
                if (itemType == "assign" && itemInstance != -1) {
                    dueDate = assignmentsMap[itemInstance]?.optLong("duedate", 0L)?.let { if (it > 0) it else null }
                } else if (itemType == "quiz" && itemInstance != -1) {
                    dueDate = quizzesMap[itemInstance]?.optLong("timeclose", 0L)?.let { if (it > 0) it else null }
                }

                val now = System.currentTimeMillis() / 1000L
                var finalStatus = status
                var finalGrade = displayGrade
                if ((finalStatus == "pending" || finalStatus == "not_submitted") && dueDate != null && now > dueDate) {
                    finalStatus = "not_submitted"
                    finalGrade = "0"
                }

                activities.add(
                    ActivityItem(
                        accountId = accountId,
                        courseId = courseId,
                        moodleActivityId = activityId,
                        name = activityName,
                        moduleType = itemType,
                        dueDate = dueDate,
                        status = finalStatus,
                        grade = formatGrade(finalGrade),
                        maxGrade = formatMaxGrade(maxGrade)
                    )
                )
            }
        }

        return activities
    }

    /**
     * Fetches quizzes by course.
     */
    suspend fun fetchQuizzesByCourse(baseUrl: String, token: String, courseId: Int): List<JSONObject> {
        val sanitizedUrl = sanitizeUrl(baseUrl)
        val url = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=mod_quiz_get_quizzes_by_courses&moodlewsrestformat=json&courseids[0]=$courseId"
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val array = json.optJSONArray("quizzes") ?: return emptyList()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until array.length()) {
                    list.add(array.getJSONObject(i))
                }
                return list
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchQuizzesByCourse failed for course $courseId", e)
            return emptyList()
        }
    }

    /**
     * Fetches student attempts for a quiz.
     */
    suspend fun fetchQuizUserAttempts(baseUrl: String, token: String, quizId: Int, userId: Int): List<JSONObject> {
        val sanitizedUrl = sanitizeUrl(baseUrl)
        val url = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=mod_quiz_get_user_attempts&moodlewsrestformat=json&quizid=$quizId&userid=$userId&includepreviews=0"
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val array = json.optJSONArray("attempts") ?: return emptyList()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until array.length()) {
                    list.add(array.getJSONObject(i))
                }
                return list
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchQuizUserAttempts failed for quiz $quizId", e)
            return emptyList()
        }
    }

    /**
     * Fetches assignments by course.
     */
    suspend fun fetchAssignmentsByCourse(baseUrl: String, token: String, courseId: Int): List<JSONObject> {
        val sanitizedUrl = sanitizeUrl(baseUrl)
        val url = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=mod_assign_get_assignments&moodlewsrestformat=json&courseids[0]=$courseId"
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val coursesArray = json.optJSONArray("courses") ?: return emptyList()
                if (coursesArray.length() == 0) return emptyList()
                val assignmentsArray = coursesArray.getJSONObject(0).optJSONArray("assignments") ?: return emptyList()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until assignmentsArray.length()) {
                    list.add(assignmentsArray.getJSONObject(i))
                }
                return list
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAssignmentsByCourse failed for course $courseId", e)
            return emptyList()
        }
    }

    /**
     * Fetches assignment submission status.
     */
    suspend fun fetchAssignmentSubmissionStatus(baseUrl: String, token: String, assignId: Int, userId: Int): JSONObject? {
        val sanitizedUrl = sanitizeUrl(baseUrl)
        val url = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=mod_assign_get_submission_status&moodlewsrestformat=json&assignid=$assignId&userid=$userId"
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                return JSONObject(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAssignmentSubmissionStatus failed for assign $assignId", e)
            return null
        }
    }

    /**
     * Fetches forums by course.
     */
    suspend fun fetchForumsByCourse(baseUrl: String, token: String, courseId: Int): List<JSONObject> {
        val sanitizedUrl = sanitizeUrl(baseUrl)
        val url = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=mod_forum_get_forums_by_courses&moodlewsrestformat=json&courseids[0]=$courseId"
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                if (body.trim().startsWith("[")) {
                    val array = JSONArray(body)
                    val list = mutableListOf<JSONObject>()
                    for (i in 0 until array.length()) {
                        list.add(array.getJSONObject(i))
                    }
                    return list
                } else {
                    val json = JSONObject(body)
                    val array = json.optJSONArray("forums")
                    if (array != null) {
                        val list = mutableListOf<JSONObject>()
                        for (i in 0 until array.length()) {
                            list.add(array.getJSONObject(i))
                        }
                        return list
                    }
                    return emptyList()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchForumsByCourse failed for course $courseId", e)
            return emptyList()
        }
    }

    private fun stripHtml(html: String?): String {
        if (html == null) return ""
        return html.replace(Regex("<[^>]*>"), "").trim()
    }

    private fun sanitizeUrl(url: String): String {
        var cleanUrl = url.trim().removeSuffix("/")
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://$cleanUrl"
        }
        return cleanUrl
    }

    private fun isGradable(mod: JSONObject, forumsMap: Map<Int, JSONObject>): Boolean {
        val modType = mod.optString("modname", "")
        
        // Si modname == "url" o "resource" o "folder" o "page" o "book" → NUNCA calificable
        if (modType == "url" || modType == "resource" || modType == "folder" || modType == "page" || modType == "book") {
            return false
        }

        // Si purpose == "assessment" → SIEMPRE calificable
        val purpose = mod.optString("purpose", "")
        if (purpose == "assessment") {
            return true
        }

        // Check completiondata.details
        val completionData = mod.optJSONObject("completiondata")
        if (completionData != null) {
            val details = completionData.optJSONArray("details")
            if (details != null) {
                var hasUseGrade = false
                var hasSubmit = false
                var hasView = false
                var hasPosts = false
                
                for (i in 0 until details.length()) {
                    val detail = details.getJSONObject(i)
                    val ruleName = detail.optString("rulename", "")
                    when (ruleName) {
                        "completionusegrade" -> hasUseGrade = true
                        "completionsubmit" -> hasSubmit = true
                        "completionview" -> hasView = true
                        "completionposts" -> hasPosts = true
                    }
                }
                
                if (hasUseGrade) return true
                if (hasSubmit) return true
                if (hasView && !hasUseGrade && !hasSubmit && !hasPosts) return false
                
                if (hasPosts) {
                    val instanceId = mod.optInt("instance", -1)
                    val forumMeta = forumsMap[instanceId]
                    if (forumMeta != null) {
                        val gradeForum = forumMeta.optString("grade_forum", "")
                        if (gradeForum.isNotEmpty() && gradeForum != "null" && gradeForum != "0") {
                            return true
                        }
                    }
                    return false
                }
            }
        }

        // Practical rules default fallback
        if (modType == "assign" || modType == "quiz" || modType == "forum") {
            return true
        }

        return false
    }

    private fun formatGrade(rawGrade: String?): String? {
                                if (rawGrade == null || rawGrade.isEmpty() || rawGrade == "null" || rawGrade == "-") return null
                                val clean = rawGrade.replace(',', '.')
                                val d = clean.toDoubleOrNull() ?: return rawGrade
                                return if (d == d.toLong().toDouble()) {
                                    d.toLong().toString()
                                } else {
                                    String.format(java.util.Locale.US, "%.1f", d)
                                }
                            }

                            private fun formatMaxGrade(rawMaxGrade: String?): String {
                                if (rawMaxGrade == null || rawMaxGrade.isEmpty() || rawMaxGrade == "null") return "10"
                                val clean = rawMaxGrade.replace(',', '.')
                                val d = clean.toDoubleOrNull() ?: return rawMaxGrade
                                return if (d == d.toLong().toDouble()) {
                                    d.toLong().toString()
                                } else {
                                    String.format(java.util.Locale.US, "%.1f", d)
                                }
                            }

    /**
     * Simple container for site info.
     */
    data class SiteInfo(
        val userId: Int,
        val fullname: String,
        val avatarUrl: String?
    )

    // --- DEMO MOCK DATA FOR DEMO MODE ---

    fun getDemoAccount(): MoodleAccount {
        return MoodleAccount(
            id = 9999,
            moodleUrl = "https://demo.moodle.org",
            username = "demostudent",
            password = "password123",
            token = "demo_token_1234567890",
            fullName = "Sophia Martinez",
            avatarUrl = "https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&q=80&w=120",
            isActive = true
        )
    }

    fun getDemoCourses(accountId: Int): List<Course> {
        return listOf(
            Course(dbId = 1, accountId = accountId, moodleCourseId = 101, fullName = "Introduction to AI & Deep Learning", shortName = "AI-101"),
            Course(dbId = 2, accountId = accountId, moodleCourseId = 102, fullName = "Full-Stack Software Development", shortName = "CS-302"),
            Course(dbId = 3, accountId = accountId, moodleCourseId = 103, fullName = "UX/UI Design & Prototyping", shortName = "UX-204")
        )
    }

    fun getDemoActivities(accountId: Int): List<ActivityItem> {
        val now = System.currentTimeMillis() / 1000L
        val oneDay = 24 * 3600L
        val list = listOf(
            // Course 101 (AI)
            ActivityItem(
                dbId = 1,
                accountId = accountId,
                courseId = 101,
                moodleActivityId = 1001,
                name = "Quiz 1: Neural Networks & Backpropagation Basics",
                moduleType = "quiz",
                dueDate = now + oneDay, // closes tomorrow
                status = "pending",
                grade = null,
                maxGrade = "10.0"
            ),
            ActivityItem(
                dbId = 2,
                accountId = accountId,
                courseId = 101,
                moodleActivityId = 1002,
                name = "Assignment 1: Build a Custom MLP Classifier in Kotlin",
                moduleType = "assign",
                dueDate = now + 4 * oneDay, // closes in 4 days
                status = "submitted",
                grade = "9.5",
                maxGrade = "10.0"
            ),
            ActivityItem(
                dbId = 3,
                accountId = accountId,
                courseId = 101,
                moodleActivityId = 1003,
                name = "Research Paper Summary: Transformers & Attention Mechanisms",
                moduleType = "assign",
                dueDate = now - 3 * oneDay, // closed 3 days ago
                status = "graded",
                grade = "8.8",
                maxGrade = "10.0"
            ),
            ActivityItem(
                dbId = 9,
                accountId = accountId,
                courseId = 101,
                moodleActivityId = 1004,
                name = "Foro de Discusión: Deep Learning en la Industria Médica",
                moduleType = "forum",
                dueDate = now + 5 * oneDay,
                status = "graded",
                grade = "9.0",
                maxGrade = "10.0"
            ),
            ActivityItem(
                dbId = 10,
                accountId = accountId,
                courseId = 101,
                moodleActivityId = 1005,
                name = "Quiz 2: Advanced Gradient Descent Optimization Techniques",
                moduleType = "quiz",
                dueDate = now - 2 * oneDay, // closed 2 days ago
                status = "pending",
                grade = null,
                maxGrade = "10.0"
            ),
            // Course 102 (CS-302)
            ActivityItem(
                dbId = 4,
                accountId = accountId,
                courseId = 102,
                moodleActivityId = 2001,
                name = "Coding Project: Jetpack Compose Navigation & State Flow",
                moduleType = "assign",
                dueDate = now + 2 * oneDay, // closes in 2 days
                status = "pending",
                grade = null,
                maxGrade = "100"
            ),
            ActivityItem(
                dbId = 5,
                accountId = accountId,
                courseId = 102,
                moodleActivityId = 2002,
                name = "Database Lab: Room SQLite Schema & KSP Compilation",
                moduleType = "assign",
                dueDate = now - 5 * oneDay, // closed 5 days ago
                status = "graded",
                grade = "98.0",
                maxGrade = "100"
            ),
            ActivityItem(
                dbId = 6,
                accountId = accountId,
                courseId = 102,
                moodleActivityId = 2003,
                name = "Exam 1: Systems Architecture & Concurrent Programming",
                moduleType = "quiz",
                dueDate = now + 10 * oneDay, // closes in 10 days
                status = "pending",
                grade = null,
                maxGrade = "100"
            ),
            ActivityItem(
                dbId = 11,
                accountId = accountId,
                courseId = 102,
                moodleActivityId = 2004,
                name = "Git & CI/CD Pipeline Automation Practice",
                moduleType = "assign",
                dueDate = now - 6 * oneDay, // closed 6 days ago
                status = "pending",
                grade = null,
                maxGrade = "100"
            ),
            // Course 103 (UX-204)
            ActivityItem(
                dbId = 7,
                accountId = accountId,
                courseId = 103,
                moodleActivityId = 3001,
                name = "Interactive Prototype: Figma Usability Iteration",
                moduleType = "assign",
                dueDate = now + 6 * oneDay, // closes in 6 days
                status = "submitted",
                grade = null,
                maxGrade = "10"
            ),
            ActivityItem(
                dbId = 8,
                accountId = accountId,
                courseId = 103,
                moodleActivityId = 3002,
                name = "Reading Assignment: Material Design 3 Styling Guidelines",
                moduleType = "assign",
                dueDate = now - 8 * oneDay, // closed 8 days ago
                status = "graded",
                grade = "10.0",
                maxGrade = "10"
            )
        )

        return list.map { activity ->
            if ((activity.status == "pending" || activity.status == "not_submitted") && activity.dueDate != null && now > activity.dueDate) {
                activity.copy(status = "not_submitted", grade = "0")
            } else {
                activity
            }
        }
    }
}
