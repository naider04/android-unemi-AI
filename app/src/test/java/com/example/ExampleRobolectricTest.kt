package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.network.MoodleApiClient
import com.example.data.db.Course
import com.example.ui.screens.FilterBar
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertNotNull(appName)
    }

    @Test
    fun `verify demo activities contains forum items`() {
        val demoActivities = MoodleApiClient.getDemoActivities(accountId = 1)
        val hasForum = demoActivities.any { it.moduleType == "forum" }
        assertTrue("Demo activities should contain a forum item", hasForum)
    }

    @Test
    fun `verify FilterBar renders successfully`() {
        val mockCourses = listOf(
            Course(dbId = 1, accountId = 1, moodleCourseId = 101, fullName = "Sistemas Distribuidos", shortName = "SD-101")
        )
        
        composeTestRule.setContent {
            FilterBar(
                courses = mockCourses,
                selectedCourseId = null,
                onCourseSelected = {},
                selectedType = null,
                onTypeSelected = {}
            )
        }
        
        composeTestRule.onNodeWithTag("filter_course_all").assertExists()
        composeTestRule.onNodeWithTag("filter_type_all").assertExists()
    }
}
