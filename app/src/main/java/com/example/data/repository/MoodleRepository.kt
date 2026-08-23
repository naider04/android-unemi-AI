import com.example.data.network.SgaGradeGroup
import com.example.data.network.CourseGradeStructure
import java.util.*
import kotlin.math.roundToInt

/**
 * Extracts and groups grade information according to platform structure (N1, N2, Exam).
 * This method processes the raw grade items to create grouped grade structures.
 * 
 * @param courseId The Moodle course ID
 * @param activities The list of activity items
 * @param gradeItems The raw grade items from SGA API
 * @return A list of CourseGradeStructure objects representing grouped grades
 */
private suspend fun extractGradeGroups(
    courseId: Int,
    courseName: String,
    activities: List<ActivityItem>,
    gradeItems: List<JSONObject>
): List<CourseGradeStructure> {
    val result = mutableListOf<CourseGradeStructure>()
    
    // Process each grade item to find N1, N2, Exam components
    for (gradeItem in gradeItems) {
        val itemType = gradeItem.optString("itemmodule", "")
        val itemInstance = gradeItem.optInt("iteminstance", -1)
        val graderaw = gradeItem.optString("graderaw", "")
        val grademax = gradeItem.optString("grademax", "10")
        val gradedatesubmitted = gradeItem.optLong("gradedatesubmitted", 0L)
        val gradedategraded = gradeItem.optLong("gradedategraded", 0L)
        
        // Extract evaluation details from the grade item
        val display = gradeItem.optString("itemname", gradeItem.optString("activityname", ""))
        val moduleType = gradeItem.optString("itemmodule", "assign")
        
        // Try to get the evaluation definition to determine type and weight
        val evalDetailsUrl = "$sanitizedUrl/webservice/rest/server.php?wstoken=$token&wsfunction=mod_${itemType}_get_$itemType&moodlewsrestformat=json&instanceid=$itemInstance"
        try {
            val evalResponse = client.newCall(Request.Builder().url(evalDetailsUrl).get().build()).execute()
            if (evalResponse.isSuccessful) {
                val evalBody = evalResponse.body?.string()
                if (evalBody != null) {
                    val evalJson = JSONObject(evalBody)
                    val evalDetails = evalJson.optJSONObject("details") ?: continue
                    
                    // Determine the type based on evaluation details
                    val evalName = evalDetails.optString("name", "")
                    val weight = when (evalName.lowercase()) {
                        "n1", "partial1" -> 35
                        "n2", "partial2" -> 35
                        "examen", "exam", "final" -> 30
                        else -> 10 // Default weight
                    }
                    
                    val status = when {
                        gradedatesubmitted != 0L -> "submitted"
                        gradedategraded != 0L -> "graded"
                        graderaw.isEmpty() || graderaw == "null" -> "pending"
                        else -> "graded"
                    }
                    
                    // Create grade group
                    val group = CourseGradeStructure(
                        name = "$courseName - $evalName",
                        n1 = if (evalName.lowercase() == "n1" || evalName.lowercase() == "partial1") {
                            SgaGradeGroup(
                                name = evalName,
                                weight = weight,
                                obtained = graderaw.toDoubleOrNull(),
                                max = grademax.toDouble(),
                                status = status
                            )
                        } else null
                    )
                    
                    // Add to result list (this is simplified - full implementation would track all three)
                    result.add(group)
                }
            }
        } catch (e: Exception) {
            // Continue processing other items
        }
    }
    
    return result
}