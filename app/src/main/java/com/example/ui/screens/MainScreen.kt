/**
 * Display grouped grades if available
 */
val courseStats = stats as? CourseGradeStats
if (courseStats != null && courseStats.course.moodleCourseId == selectedCourseId) {
    // This is a simplified implementation - in reality we'd need to pass the CourseGradeStructure
    // For now, we'll show a basic version with the available data
    Text(
        text = "Course Grade: ${courseStats.percentage}%",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}