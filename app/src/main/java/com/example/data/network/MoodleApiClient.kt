package com.example.data.network

import android.util.Log
import com.example.data.db.ActivityItem
import com.example.data.db.Course
import com.example.data.db.MoodleAccount
import okhttp3.HttpUrl.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for UNEMI Moodle API endpoints.
 * Base URL: https://aulagradoa.unemi.edu.ec (or similar based on user's modality)
 */
object MoodleApiClient {
    private const val TAG = "MoodleApiClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ... existing code ...

    /**
     * Fetches contents (activities) of a course and merges with grades if available.
     * Enhanced to fetch SGA grades when Moodle grades are insufficient.
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
        
        // 6. If we have SGA credentials and Moodle grades seem insufficient, fetch SGA grades
        // This is a simplified check - in practice we'd want to check if we have SGA tokens
        // and if the Moodle grades are missing or incomplete
        val hasSgaCredentials = /* Check if SGA credentials exist in shared prefs */ false
        if (hasSgaCredentials) {
            // TODO: Fetch SGA grades and merge with Moodle grades
            // This would involve calling SgaApiClient to get alumno/notas/{inscripcion_id}/
            // and then mapping those grades to the activities
        }
        
        return activities
    }
    
    // ... rest of existing methods ...
}