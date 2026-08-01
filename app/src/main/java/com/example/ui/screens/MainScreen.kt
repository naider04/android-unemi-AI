package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.db.ActivityItem
import com.example.data.db.Course
import com.example.data.db.MoodleAccount
// Note import removed
import com.example.data.db.NotificationRule
import com.example.ui.viewmodel.ChatMessage
import com.example.ui.viewmodel.MoodleViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(viewModel: MoodleViewModel) {
    val activeAccount by viewModel.activeAccount.collectAsState()
    val allAccounts by viewModel.allAccounts.collectAsState()
    val courses by viewModel.courses.collectAsState()
    val activities by viewModel.activities.collectAsState()
    val alarms by viewModel.notifications.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    val isAlarmRinging by viewModel.isAlarmRinging.collectAsState()
    val ringingAlarmTitle by viewModel.ringingAlarmTitle.collectAsState()
    val ringingAlarmBody by viewModel.ringingAlarmBody.collectAsState()

    if (isAlarmRinging) {
        Dialog(
            onDismissRequest = { /* User must click Dismiss to turn off alarm */ },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("ringing_alarm_dialog"),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = "Alarm Ringing",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .size(64.dp)
                            .padding(bottom = 16.dp)
                    )
                    Text(
                        text = "ALARM RINGING!",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = ringingAlarmTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = ringingAlarmBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                    Button(
                        onClick = { viewModel.dismissAlarm() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("dismiss_alarm_button")
                    ) {
                        Text(
                            text = "DISMISS ALARM",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }

    var currentSection by remember { mutableStateOf(1) } // 1: AI, 2: Grades, 3: Alarms, 4: Subjects, 5: Chronogram
    var showSettings by remember { mutableStateOf(false) }
    var selectedCourseIdFilter by remember { mutableStateOf<Int?>(null) }
    var selectedTypeFilter by remember { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            if (activeAccount?.avatarUrl != null) {
                                AsyncImage(
                                    model = activeAccount!!.avatarUrl,
                                    contentDescription = "Avatar",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.School,
                                    contentDescription = "Moodle AI Logo",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Moodle AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = activeAccount?.fullName ?: "Guest / Config Account",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (activeAccount != null) {
                        IconButton(
                            onClick = { viewModel.syncMoodle() },
                            modifier = Modifier.testTag("sync_button")
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Sync Moodle")
                            }
                        }
                    }
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Configurations")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                )
            )
        },
        bottomBar = {
            if (!WindowInsets.isImeVisible) {
                NavigationBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                ) {
                    NavigationBarItem(
                        selected = currentSection == 1,
                        onClick = { currentSection = 1 },
                        label = { Text("AI Portal") },
                        icon = { Icon(if (currentSection == 1) Icons.Default.SmartToy else Icons.Outlined.SmartToy, contentDescription = "AI Portal") },
                        modifier = Modifier.testTag("nav_ai")
                    )
                    NavigationBarItem(
                        selected = currentSection == 2,
                        onClick = { currentSection = 2 },
                        label = { Text("Grades") },
                        icon = { Icon(if (currentSection == 2) Icons.Default.Grade else Icons.Outlined.Grade, contentDescription = "Grades") },
                        modifier = Modifier.testTag("nav_grades")
                    )
                    NavigationBarItem(
                        selected = currentSection == 3,
                        onClick = { currentSection = 3 },
                        label = { Text("Alarms") },
                        icon = { Icon(if (currentSection == 3) Icons.Default.NotificationsActive else Icons.Outlined.NotificationsActive, contentDescription = "Alarms") },
                        modifier = Modifier.testTag("nav_alarms")
                    )
                    NavigationBarItem(
                        selected = currentSection == 4,
                        onClick = { currentSection = 4 },
                        label = { Text("Subjects") },
                        icon = { Icon(if (currentSection == 4) Icons.Default.MenuBook else Icons.Outlined.MenuBook, contentDescription = "Subjects") },
                        modifier = Modifier.testTag("nav_subjects")
                    )
                    NavigationBarItem(
                        selected = currentSection == 5,
                        onClick = { currentSection = 5 },
                        label = { Text("Chronogram") },
                        icon = { Icon(if (currentSection == 5) Icons.Default.CalendarToday else Icons.Outlined.CalendarToday, contentDescription = "Chronogram") },
                        modifier = Modifier.testTag("nav_chrono")
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = currentSection,
                    transitionSpec = {
                        (slideInHorizontally { width -> if (targetState > initialState) width else -width } + fadeIn())
                            .togetherWith(slideOutHorizontally { width -> if (targetState > initialState) -width else width } + fadeOut())
                    },
                    label = "SectionTransition"
                ) { section ->
                    when (section) {
                        1 -> AiAssistantScreen(viewModel)
                        2 -> GradesScreen(viewModel, courses, activities, selectedCourseIdFilter, selectedTypeFilter)
                        3 -> AlarmsScreen(viewModel, alarms)
                        4 -> SubjectsScreen(viewModel, allAccounts, courses, activities)
                        5 -> ChronogramScreen(viewModel, allAccounts, activities, courses, selectedCourseIdFilter, selectedTypeFilter)
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            viewModel = viewModel,
            courses = courses,
            selectedCourseId = selectedCourseIdFilter,
            onCourseSelected = { selectedCourseIdFilter = it },
            selectedType = selectedTypeFilter,
            onTypeSelected = { selectedTypeFilter = it },
            onDismiss = { showSettings = false }
        )
    }
}

// ==========================================
// 1. AI PORTAL SCREEN
// ==========================================
@Composable
fun AiAssistantScreen(viewModel: MoodleViewModel) {
    val messages = viewModel.chatMessages
    val isLoading by viewModel.isChatLoading.collectAsState()
    val chatProgressStatus by viewModel.chatProgressStatus.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isLoading) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // AI Header Bar
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Moodle AI Assistant",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = { viewModel.clearChatHistory() },
                    modifier = Modifier.testTag("clear_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Clear Chat History",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Chat History
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(messages) { message ->
                ChatBubble(message = message)
            }
            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    text = chatProgressStatus ?: "Moodle AI is thinking...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        // Input Area
        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask about grades, tasks, alarms, connected accounts...") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input"),
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank() && !isLoading) {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    })
                )
                FloatingActionButton(
                    onClick = {
                        if (inputText.isNotBlank() && !isLoading) {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("send_button"),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send message")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.sender == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val bubbleShape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = containerColor),
            shape = bubbleShape,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                SelectionContainer {
                    MarkdownText(text = message.text, color = contentColor)
                }
            }
        }

        // Action confirmation from AI (saved note or scheduled notification)
        if (message.actionApplied != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .align(if (isUser) Alignment.End else Alignment.Start)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.TaskAlt,
                        contentDescription = "Action applied",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    MarkdownText(
                        text = message.actionApplied,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            val context = LocalContext.current
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Chat Message", message.text)
                    clipboard?.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .size(20.dp)
                    .testTag("copy_chat_message_button_${message.timestamp}")
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy message",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

// ==========================================
// 2. GRADES ANALYSIS SCREEN
// ==========================================
@Composable
fun GradesScreen(
    viewModel: MoodleViewModel,
    courses: List<Course>,
    activities: List<ActivityItem>,
    selectedCourseId: Int? = null,
    selectedType: String? = null
) {
    var expandedCourseDbId by remember { mutableStateOf<Int?>(null) }

    // For each course, calculate the grade statistics
    val courseGradesList = remember(courses, activities, selectedCourseId, selectedType) {
        val filteredCourses = if (selectedCourseId != null) {
            courses.filter { it.moodleCourseId == selectedCourseId }
        } else {
            courses
        }

        filteredCourses.map { course ->
            val courseActivities = activities.filter { 
                it.accountId == course.accountId && 
                it.courseId == course.moodleCourseId &&
                (selectedType == null || it.moduleType == selectedType)
            }
            val gradedActivities = courseActivities.filter { it.grade != null }
            
            var sumGrades = 0.0
            var sumMaxGrades = 0.0
            
            gradedActivities.forEach { act ->
                val g = parseGradeToDouble(act.grade)
                val m = parseGradeToDouble(act.maxGrade)
                if (g != null && m != null) {
                    sumGrades += g
                    sumMaxGrades += m
                }
            }
            
            val percentage = if (sumMaxGrades > 0.0) (sumGrades / sumMaxGrades) * 100.0 else 0.0
            
            CourseGradeStats(
                course = course,
                sumGrades = sumGrades,
                sumMaxGrades = sumMaxGrades,
                percentage = percentage,
                gradedCount = gradedActivities.size,
                totalCount = courseActivities.size
            )
        }.sortedBy { it.percentage } // Sorted with lowest percentage on top
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                "Subject Grades Analysis",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Text(
                "Analyze your performance per subject. Click on a subject to see detailed grades for individual evaluations. Ordered with lowest grades on top.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        if (courseGradesList.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Grade,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "No grades found",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Connect to Moodle or switch to Demo Mode to view and analyze your grades.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(courseGradesList) { stats ->
                val isExpanded = expandedCourseDbId == stats.course.dbId
                val percentageFormatted = String.format(Locale.US, "%.1f", stats.percentage)
                val sumGradesFormatted = String.format(Locale.US, "%.2f", stats.sumGrades)
                val sumMaxGradesFormatted = String.format(Locale.US, "%.2f", stats.sumMaxGrades)
                
                // Color coding based on grade percentage
                val progressColor = when {
                    stats.percentage < 60.0 -> Color(0xFFD32F2F) // Red - Struggling
                    stats.percentage < 80.0 -> Color(0xFFF57C00) // Orange - Average
                    else -> Color(0xFF388E3C) // Green - Excellent
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedCourseDbId = if (isExpanded) null else stats.course.dbId }
                        .testTag("grade_item_${stats.course.moodleCourseId}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(if (isExpanded) 2.dp else 1.dp)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stats.course.fullName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(12.dp))

                            // Big Percentage Badge
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "$percentageFormatted%",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Black,
                                    color = progressColor
                                )
                                Text(
                                    text = "${stats.gradedCount}/${stats.totalCount} graded",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Linear Progress Indicator
                        LinearProgressIndicator(
                            progress = (stats.percentage / 100.0).toFloat().coerceIn(0f, 1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = progressColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Calculate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Total Obtained: $sumGradesFormatted / $sumMaxGradesFormatted",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Visual Status Label
                            val statusLabel = when {
                                stats.percentage < 60.0 -> "Attention Required"
                                stats.percentage < 80.0 -> "On Track"
                                else -> "Excellent"
                            }
                            Box(
                                modifier = Modifier
                                    .background(progressColor.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = statusLabel.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = progressColor
                                )
                            }
                        }

                        if (isExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                text = "Evaluations & Activities",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            
                            val courseActivities = activities.filter { 
                                it.accountId == stats.course.accountId && 
                                it.courseId == stats.course.moodleCourseId &&
                                (selectedType == null || it.moduleType == selectedType)
                            }
                            if (courseActivities.isEmpty()) {
                                Text(
                                    text = "No evaluations registered for this subject.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    courseActivities.forEach { activity ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = activity.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                val dueDateStr = activity.dueDate?.let {
                                                    val sdf = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
                                                    "Due: " + sdf.format(Date(it * 1000L))
                                                }
                                                if (dueDateStr != null) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = dueDateStr,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                    )
                                                }
                                            }
                                            
                                            if (activity.grade != null) {
                                                val maxGradeText = activity.maxGrade ?: "10"
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = "${activity.grade} / $maxGradeText",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (activity.status == "not_submitted") Color(0xFFD32F2F) else progressColor
                                                    )
                                                    Text(
                                                        text = if (activity.status == "not_submitted") "Not Submitted" else "Graded",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            } else {
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = if (activity.status == "submitted") "N/A" else "- / ${activity.maxGrade ?: "10"}",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                    )
                                                    Text(
                                                        text = if (activity.status == "submitted") "Grade Not Available" else "Pending",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class CourseGradeStats(
    val course: Course,
    val sumGrades: Double,
    val sumMaxGrades: Double,
    val percentage: Double,
    val gradedCount: Int,
    val totalCount: Int
)

fun parseGradeToDouble(gradeStr: String?): Double? {
    if (gradeStr == null) return null
    val clean = gradeStr.trim().replace(",", ".")
    return clean.toDoubleOrNull()
}

// ==========================================
// 3. PROGRAMMED NOTIFICATIONS / ALARMS
// ==========================================
@Composable
fun AlarmsScreen(viewModel: MoodleViewModel, alarms: List<NotificationRule>) {
    var expandedAlarmIds by remember { mutableStateOf(setOf<Int>()) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                "Coded Alarms & Scheduled Tasks",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Text(
                "Moodle AI schedules dynamic programmatic alarms based on activity due dates. The alarms are represented as evaluated execution scripts. Click any alarm to view its code script.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        if (alarms.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "No scripted notifications programmed",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tell the Moodle AI: \"Remind me 2 hours before my CS302 assignment closes\" to see your custom evaluated rules.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(alarms) { alarm ->
                val isExpanded = expandedAlarmIds.contains(alarm.id)
                val context = LocalContext.current
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedAlarmIds = if (isExpanded) {
                                expandedAlarmIds - alarm.id
                            } else {
                                expandedAlarmIds + alarm.id
                            }
                        }
                        .testTag("alarm_item_${alarm.id}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (alarm.ruleType == "ALARM") Icons.Default.Alarm else Icons.Default.Notifications,
                                        contentDescription = null,
                                        size = 18.sp,
                                        tint = if (alarm.ruleType == "ALARM") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = alarm.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (alarm.ruleType == "ALARM") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = alarm.ruleType,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (alarm.ruleType == "ALARM") MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = alarm.body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.deleteNotificationDirectly(alarm.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Alarm", tint = MaterialTheme.colorScheme.error)
                            }
                        }

                        if (isExpanded) {
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            // Styled code script
                            Surface(
                                color = Color(0xFF1E1E24),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "AI EVALUATION RULE SCRIPT",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.LightGray
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (alarm.isActive) Color(0xFF2E7D32) else Color(0xFF616161),
                                                        shape = RoundedCornerShape(4.dp)
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = if (alarm.isActive) "MONITORING" else "TRIGGERED",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                                    val clip = android.content.ClipData.newPlainText("Rule Script", alarm.triggerCode)
                                                    clipboard?.setPrimaryClip(clip)
                                                    android.widget.Toast.makeText(context, "Script copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .testTag("copy_script_button_${alarm.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ContentCopy,
                                                    contentDescription = "Copy Script",
                                                    tint = Color.LightGray,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    SelectionContainer {
                                        Text(
                                            text = alarm.triggerCode,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = Color(0xFF64FFDA)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.AccessTime, contentDescription = null, size = 14.sp, tint = MaterialTheme.colorScheme.primary)
                                val statusLabel = when {
                                    alarm.triggerType == "CUSTOM_CODE" -> {
                                        if (alarm.isActive) {
                                            "Actively monitoring live Moodle data"
                                        } else {
                                            "Triggered / Evaluation completed"
                                        }
                                    }
                                    else -> {
                                        if (alarm.isActive) {
                                            "Scheduled to trigger at:\n${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(alarm.timeScheduled))}"
                                        } else {
                                            "Triggered at:\n${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(alarm.timeScheduled))}"
                                        }
                                    }
                                }
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            Row(
                                modifier = Modifier.padding(start = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = if (isExpanded) "Hide script" else "Show script",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    size = 14.sp,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper to provide a custom Icon size for Text-like composables
@Composable
private fun Icon(imageVector: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String?, size: androidx.compose.ui.unit.TextUnit, tint: Color) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = Modifier.size(size.value.dp),
        tint = tint
    )
}

// ==========================================
// 4. SUBJECTS & ACTIVITIES
// ==========================================
@Composable
fun SubjectsScreen(
    viewModel: MoodleViewModel,
    allAccounts: List<MoodleAccount>,
    courses: List<Course>,
    activities: List<ActivityItem>
) {
    var expandedCourseDbId by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                "Moodle Subjects & Activities",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Text(
                "Browse through your matriculated courses, see grades, tasks, and submission statuses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        if (courses.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.School,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "No courses synchronized",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Log in with Moodle or enable Demo Mode in Configurations to sync courses.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(courses) { course ->
                val isExpanded = expandedCourseDbId == course.dbId
                val courseActivities = activities.filter { it.accountId == course.accountId && it.courseId == course.moodleCourseId }
                val account = allAccounts.firstOrNull { it.id == course.accountId }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedCourseDbId = if (isExpanded) null else course.dbId }
                        .testTag("course_item_${course.moodleCourseId}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(if (isExpanded) 2.dp else 1.dp)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer,
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = course.shortName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${courseActivities.size} Activities",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (account != null) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "•  ${account.fullName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = course.fullName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }

                        if (isExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            if (courseActivities.isEmpty()) {
                                Text(
                                    "No activities or grades listed under this course.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    courseActivities.forEach { activity ->
                                        ActivityRow(activity)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityRow(activity: ActivityItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when (activity.moduleType) {
                            "quiz" -> Icons.Default.Quiz
                            "assign" -> Icons.Default.Assignment
                            "forum" -> Icons.Default.Forum
                            else -> Icons.Default.InsertDriveFile
                        },
                        contentDescription = activity.moduleType,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = activity.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Grade Indicator
                if (activity.grade != null) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (activity.status == "not_submitted") Color(0xFFFFEBEE) else MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (activity.status == "not_submitted") "0 / ${activity.maxGrade} (NOT SUBMITTED)" else "${activity.grade} / ${activity.maxGrade}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (activity.status == "not_submitted") Color(0xFFC62828) else MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .background(
                                if (activity.status == "submitted") Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (activity.status == "submitted") "GRADE N/A" else "PENDING",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (activity.status == "submitted") Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                    }
                }
            }

            if (activity.dueDate != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, size = 11.sp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "Closes: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(activity.dueDate * 1000L))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ==========================================
// 5. CHRONOGRAM / CALENDAR SCREEN
// ==========================================
@Composable
fun ChronogramScreen(
    viewModel: MoodleViewModel,
    allAccounts: List<MoodleAccount>,
    activities: List<ActivityItem>,
    courses: List<Course>,
    selectedCourseId: Int? = null,
    selectedType: String? = null
) {
    // Sort all activities with valid closing dates
    val chronogramList = remember(activities, selectedCourseId, selectedType) {
        activities
            .filter { 
                it.dueDate != null &&
                (selectedCourseId == null || it.courseId == selectedCourseId) &&
                (selectedType == null || it.moduleType == selectedType)
            }
            .sortedBy { it.dueDate!! }
    }

    val now = remember { System.currentTimeMillis() }
    val pastActivities = remember(chronogramList, now) {
        chronogramList.filter { (it.dueDate ?: 0L) * 1000L < now }
    }
    val futureActivities = remember(chronogramList, now) {
        chronogramList.filter { (it.dueDate ?: 0L) * 1000L >= now }
    }

    val defaultCounts = remember(pastActivities, futureActivities) {
        val pSize = pastActivities.size
        val fSize = futureActivities.size
        if (pSize + fSize <= 10) {
            Pair(pSize, fSize)
        } else if (pSize >= 5 && fSize >= 5) {
            Pair(5, 5)
        } else if (pSize < 5) {
            Pair(pSize, 10 - pSize)
        } else {
            Pair(10 - fSize, fSize)
        }
    }

    var pastLimit by remember(defaultCounts) { mutableStateOf(defaultCounts.first) }
    var futureLimit by remember(defaultCounts) { mutableStateOf(defaultCounts.second) }

    val visiblePast = remember(pastActivities, pastLimit) {
        pastActivities.takeLast(pastLimit)
    }
    val visibleFuture = remember(futureActivities, futureLimit) {
        futureActivities.take(futureLimit)
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                "Academic Chronogram",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Text(
                "Real-time timelines of quizzes, tests, and deliverables ordered strictly by their closing deadlines.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        if (chronogramList.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Timeline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "No deadlines scheduled",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Sync Moodle to fetch activities or explore Demo Mode.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            // Show more past activities button at the top
            if (pastActivities.size > pastLimit) {
                item {
                    OutlinedButton(
                        onClick = { pastLimit = minOf(pastActivities.size, pastLimit + 10) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Show 10 more from the past (${pastActivities.size - pastLimit} remaining)")
                    }
                }
            }

            // Past activities
            items(visiblePast) { activity ->
                ChronogramCardItem(activity, courses, allAccounts)
            }

            // TODAY divider / indicator
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "TODAY / CURRENT TIME",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                }
            }

            // Future activities
            items(visibleFuture) { activity ->
                ChronogramCardItem(activity, courses, allAccounts)
            }

            // Show more future activities button at the bottom
            if (futureActivities.size > futureLimit) {
                item {
                    OutlinedButton(
                        onClick = { futureLimit = minOf(futureActivities.size, futureLimit + 10) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Show 10 more in the future (${futureActivities.size - futureLimit} remaining)")
                    }
                }
            }
        }
    }
}

@Composable
fun ChronogramCardItem(
    activity: ActivityItem,
    courses: List<Course>,
    allAccounts: List<MoodleAccount>
) {
    val courseName = courses.firstOrNull { it.accountId == activity.accountId && it.moodleCourseId == activity.courseId }?.fullName ?: "Course"
    val account = allAccounts.firstOrNull { it.id == activity.accountId }
    val closingTimestamp = activity.dueDate!! * 1000L
    val timeRemainingMs = closingTimestamp - System.currentTimeMillis()
    
    // Color tagging for urgency
    val urgentColor = when {
        timeRemainingMs < 0 -> Color.Gray
        timeRemainingMs < 24 * 3600 * 1000L -> Color(0xFFD32F2F) // closes under 24 hrs -> Red
        timeRemainingMs < 3 * 24 * 3600 * 1000L -> Color(0xFFF57C00) // closes under 3 days -> Orange
        else -> Color(0xFF388E3C) // Green
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Colored status bar left edge
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(urgentColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = courseName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (account != null) {
                        Text(
                            text = account.fullName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = activity.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when (activity.moduleType) {
                                "quiz" -> Icons.Default.Quiz
                                "forum" -> Icons.Default.Forum
                                else -> Icons.Default.Assignment
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = activity.moduleType.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = if (timeRemainingMs < 0) {
                            "PAST DUE"
                        } else {
                            val days = timeRemainingMs / (24 * 3600 * 1000L)
                            val hours = (timeRemainingMs % (24 * 3600 * 1000L)) / (3600 * 1000L)
                            if (days > 0) "CLOSING IN $days days" else "CLOSING IN $hours hrs!"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = urgentColor
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, size = 12.sp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = SimpleDateFormat("EEEE, yyyy-MM-dd 'at' HH:mm", Locale.getDefault()).format(Date(closingTimestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ==========================================
// CONFIGURATIONS DIALOG / sheet
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    viewModel: MoodleViewModel,
    courses: List<Course>,
    selectedCourseId: Int?,
    onCourseSelected: (Int?) -> Unit,
    selectedType: String?,
    onTypeSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val accounts by viewModel.allAccounts.collectAsState()
    val activeAccount by viewModel.activeAccount.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val apiKeyValue by viewModel.geminiApiKey.collectAsState()
    val useOpenCode by viewModel.useOpenCode.collectAsState()
    val openCodeApiKey by viewModel.openCodeApiKey.collectAsState()
    val openCodeModel by viewModel.openCodeModel.collectAsState()
    val deactivateThinking by viewModel.deactivateThinking.collectAsState()

    var showAddAccount by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Configurations") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Close")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                        )
                    )
                }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Global Filters Configuration
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Global Content Filters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "Filter Grades and Chronogram sections by subject or evaluation type.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Text("Filter by Subject", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    item {
                                        FilterChip(
                                            selected = selectedCourseId == null,
                                            onClick = { onCourseSelected(null) },
                                            label = { Text("All Subjects 📚") },
                                            modifier = Modifier.testTag("filter_course_all")
                                        )
                                    }
                                    items(courses) { course ->
                                        FilterChip(
                                            selected = selectedCourseId == course.moodleCourseId,
                                            onClick = { onCourseSelected(course.moodleCourseId) },
                                            label = { Text(course.shortName.ifEmpty { course.fullName }) },
                                            modifier = Modifier.testTag("filter_course_${course.moodleCourseId}")
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Filter by Evaluation Type", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    item {
                                        FilterChip(
                                            selected = selectedType == null,
                                            onClick = { onTypeSelected(null) },
                                            label = { Text("All Types ⚙️") },
                                            modifier = Modifier.testTag("filter_type_all")
                                        )
                                    }
                                    val types = listOf(
                                        Pair("assign", "Assignments 📝"),
                                        Pair("quiz", "Quizzes 🧠"),
                                        Pair("forum", "Forums 💬")
                                    )
                                    items(types) { (typeKey, label) ->
                                        FilterChip(
                                            selected = selectedType == typeKey,
                                            onClick = { onTypeSelected(typeKey) },
                                            label = { Text(label) },
                                            modifier = Modifier.testTag("filter_type_$typeKey")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 1. Theme Configuration
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Visual Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Toggle dark & eye-safe themes", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = isDarkTheme,
                                    onCheckedChange = { viewModel.setDarkTheme(it) },
                                    modifier = Modifier.testTag("theme_switch")
                                )
                            }
                        }
                    }

                    // 2. AI Service Provider Settings
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("AI Service Provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "Choose which AI engine to use for study planning, query answering, and notification automation.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Gemini Option
                                    FilterChip(
                                        selected = !useOpenCode,
                                        onClick = { viewModel.setUseOpenCode(false) },
                                        label = { Text("Gemini AI 🤖") },
                                        modifier = Modifier.weight(1f).testTag("select_gemini_provider")
                                    )
                                    // OpenCode Option
                                    FilterChip(
                                        selected = useOpenCode,
                                        onClick = { viewModel.setUseOpenCode(true) },
                                        label = { Text("OpenCode AI 💻") },
                                        modifier = Modifier.weight(1f).testTag("select_opencode_provider")
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                if (!useOpenCode) {
                                    // Gemini Key Configuration
                                    OutlinedTextField(
                                        value = apiKeyValue,
                                        onValueChange = { viewModel.setGeminiApiKey(it) },
                                        label = { Text("Gemini API Key") },
                                        placeholder = { Text("Enter Gemini API key") },
                                        modifier = Modifier.fillMaxWidth().testTag("api_key_input"),
                                        singleLine = true,
                                        trailingIcon = {
                                            Icon(Icons.Default.VpnKey, contentDescription = null)
                                        }
                                    )
                                } else {
                                    // OpenCode Configuration
                                    OutlinedTextField(
                                        value = openCodeApiKey,
                                        onValueChange = { viewModel.setOpenCodeApiKey(it) },
                                        label = { Text("OpenCode API Key") },
                                        placeholder = { Text("Enter OpenCode API key") },
                                        modifier = Modifier.fillMaxWidth().testTag("opencode_api_key_input"),
                                        singleLine = true,
                                        trailingIcon = {
                                            Icon(Icons.Default.VpnKey, contentDescription = null)
                                        }
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Text("OpenCode Model Selector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    
                                    val predefinedModels = listOf("FREE: Big Pickle", "nemutron 3 ultra free")
                                    var customModelInputActive by remember { mutableStateOf(!predefinedModels.contains(openCodeModel)) }
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        predefinedModels.forEach { modelName ->
                                            val isSelected = !customModelInputActive && openCodeModel == modelName
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    customModelInputActive = false
                                                    viewModel.setOpenCodeModel(modelName)
                                                },
                                                label = { Text(modelName) },
                                                modifier = Modifier.weight(1f).testTag("opencode_model_${modelName.replace(" ", "_").replace(":", "")}")
                                            )
                                        }
                                        FilterChip(
                                            selected = customModelInputActive,
                                            onClick = {
                                                customModelInputActive = true
                                            },
                                            label = { Text("Custom ✏️") },
                                            modifier = Modifier.weight(1f).testTag("opencode_model_custom_chip")
                                        )
                                    }

                                    if (customModelInputActive) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = if (predefinedModels.contains(openCodeModel)) "" else openCodeModel,
                                            onValueChange = { viewModel.setOpenCodeModel(it) },
                                            label = { Text("Custom Model ID") },
                                            placeholder = { Text("e.g. deepseek-chat") },
                                            modifier = Modifier.fillMaxWidth().testTag("opencode_custom_model_input"),
                                            singleLine = true
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text("Deactivate Thinking Mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "Hides model system thought tags (<think>...</think>) to return direct answers.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = deactivateThinking,
                                            onCheckedChange = { viewModel.setDeactivateThinking(it) },
                                            modifier = Modifier.testTag("deactivate_thinking_switch")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Accounts Management
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Matriculated Moodle Accounts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Button(
                                onClick = { showAddAccount = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("add_account_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add")
                            }
                        }
                    }

                    if (accounts.isEmpty()) {
                        item {
                            Surface(
                                tonalElevation = 1.dp,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.addMoodleAccount("demo", "demo", "demo", {}, {})
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(Icons.Default.CastForEducation, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Explore in Demo Mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                        Text("Instant access with simulated subjects, grades, and alarms.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                                }
                            }
                        }
                    } else {
                        items(accounts) { account ->
                            val isActive = activeAccount?.id == account.id
                            Card(
                                modifier = Modifier.fillMaxWidth().testTag("account_item_${account.id}"),
                                border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (account.avatarUrl != null) {
                                            AsyncImage(
                                                model = account.avatarUrl,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Icon(Icons.Default.Person, contentDescription = null)
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(account.fullName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                        Text(account.moodleUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("User: ${account.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row {
                                        if (!isActive) {
                                            IconButton(onClick = { viewModel.switchAccount(account.id) }) {
                                                Icon(Icons.Default.SwapHoriz, contentDescription = "Switch to Account")
                                            }
                                        }
                                        IconButton(onClick = { viewModel.deleteAccount(account.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Account", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. UNEMI SGA Estudiante JWT Auth Integration Card
                    item {
                        val sgaToken by viewModel.sgaAccessToken.collectAsState()
                        val sgaPayload by viewModel.sgaSessionPayload.collectAsState()
                        val sgaValid by viewModel.sgaSessionValid.collectAsState()
                        val sgaExpiry by viewModel.sgaExpiresAtMillis.collectAsState()
                        var showSgaDialog by remember { mutableStateOf(false) }

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (sgaToken.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AccountBalance,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "UNEMI SGA Estudiante",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    if (sgaToken.isNotEmpty()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { viewModel.refreshSgaNow { showSgaDialog = true } }
                                            ) {
                                                Text(if (sgaValid) "Refresh" else "Reconnect")
                                            }
                                            OutlinedButton(
                                                onClick = { viewModel.clearSgaSession() },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("Disconnect")
                                            }
                                        }
                                    } else {
                                        Button(onClick = { showSgaDialog = true }) {
                                            Text("Connect SGA")
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                if (sgaToken.isNotEmpty()) {
                                    val decodedUser = remember(sgaPayload) {
                                        try {
                                            val json = org.json.JSONObject(sgaPayload)
                                            val p = json.optJSONObject("persona")
                                            val nombres = p?.optString("nombres") ?: json.optJSONObject("user")?.optString("username") ?: "Connected Student"
                                            val doc = p?.optString("documento") ?: ""
                                            "$nombres (CI: $doc)"
                                        } catch (e: Exception) {
                                            "Active Session"
                                        }
                                    }
                                    Text(
                                        text = when {
                                            !sgaValid -> "Status: Session Expired ($decodedUser)"
                                            sgaExpiry > 0L -> "Status: Connected ($decodedUser) — expires ${formatSgaExpiry(sgaExpiry)}"
                                            else -> "Status: Connected ($decodedUser)"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (sgaValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = "The AI companion can query grades, malla, exams, schedule, finances, and events credential-free via JWT token.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = "Status: Not Connected",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Connect once to authorize chatbot query access for SGA grades, malla, exams, attendance, and finances.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (showSgaDialog) {
                            AddSgaAccountDialog(
                                viewModel = viewModel,
                                onDismiss = { showSgaDialog = false }
                            )
                        }
                    }
                }
            }

            if (showAddAccount) {
                AddAccountDialog(
                    viewModel = viewModel,
                    onDismiss = { showAddAccount = false }
                )
            }
        }
    }
}

@Composable
fun AddAccountDialog(viewModel: MoodleViewModel, onDismiss: () -> Unit) {
    var moodleUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log in to Moodle Site") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "This authenticates via standard Moodle Mobile API token protocol. If you don't have a live site, type 'demo' in Moodle Link to test.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                OutlinedTextField(
                    value = moodleUrl,
                    onValueChange = { moodleUrl = it; errorMsg = null },
                    label = { Text("Moodle Link (URL)") },
                    placeholder = { Text("e.g. moodle.university.edu") },
                    modifier = Modifier.fillMaxWidth().testTag("login_url"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMsg = null },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth().testTag("login_username"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMsg = null },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth().testTag("login_password"),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )

                if (errorMsg != null) {
                    Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (moodleUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                        loading = true
                        viewModel.addMoodleAccount(
                            url = moodleUrl,
                            username = username,
                            pass = password,
                            onSuccess = {
                                loading = false
                                onDismiss()
                            },
                            onError = { err ->
                                loading = false
                                errorMsg = err
                            }
                        )
                    } else {
                        errorMsg = "Please fill in all details."
                    }
                },
                modifier = Modifier.testTag("login_submit_button"),
                enabled = !loading
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Authenticate")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text("Cancel")
            }
        }
    )
}

private fun formatSgaExpiry(epochMillis: Long): String {
    return try {
        java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(epochMillis))
    } catch (e: Exception) {
        ""
    }
}

@Composable
fun AddSgaAccountDialog(viewModel: MoodleViewModel, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountBalance, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("UNEMI SGA Estudiante Login")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Authenticates with UNEMI SGA (sgaestudiante.unemi.edu.ec) to obtain JWT session tokens. Your password is used once to fetch tokens and is never sent to AI models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMsg = null },
                    label = { Text("SGA Username (e.g. wpatinoc)") },
                    modifier = Modifier.fillMaxWidth().testTag("sga_login_username"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMsg = null },
                    label = { Text("SGA Password") },
                    modifier = Modifier.fillMaxWidth().testTag("sga_login_password"),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )

                if (errorMsg != null) {
                    Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (username.isNotBlank() && password.isNotBlank()) {
                        loading = true
                        viewModel.loginSga(
                            user = username.trim(),
                            pass = password.trim(),
                            onSuccess = {
                                loading = false
                                onDismiss()
                            },
                            onError = { err ->
                                loading = false
                                errorMsg = err
                            }
                        )
                    } else {
                        errorMsg = "Please enter both SGA username and password."
                    }
                },
                modifier = Modifier.testTag("sga_login_submit_button"),
                enabled = !loading
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Connect SGA")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text("Cancel")
            }
        }
    )
}

// ==========================================
// HIGH PERFORMANCE LIGHTWEIGHT CUSTOM MARKDOWN PARSER WITH TABLE, CODE BLOCK & LINE SUPPORT
// ==========================================
sealed class MarkdownBlock {
    data class Text(val content: String) : MarkdownBlock()
    object Divider : MarkdownBlock()
    data class Table(val headers: List<String>, val alignments: List<TableAlignment>, val rows: List<List<String>>) : MarkdownBlock()
    data class CodeBlock(val language: String, val content: String) : MarkdownBlock()
}

enum class TableAlignment {
    LEFT, CENTER, RIGHT
}

fun isSeparatorRow(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    val clean = trimmed.replace("|", "").replace("-", "").replace(":", "").replace(" ", "")
    return clean.isEmpty() && trimmed.contains("|") && trimmed.contains("-")
}

fun parseTableRow(line: String): List<String> {
    var trimmed = line.trim()
    if (trimmed.startsWith("|")) {
        trimmed = trimmed.substring(1)
    }
    if (trimmed.endsWith("|")) {
        trimmed = trimmed.substring(0, trimmed.length - 1)
    }
    return trimmed.split("|").map { it.trim() }
}

fun parseTableAlignments(separatorLine: String): List<TableAlignment> {
    val cells = parseTableRow(separatorLine)
    return cells.map { cell ->
        val trimmed = cell.trim()
        val startsWithColon = trimmed.startsWith(":")
        val endsWithColon = trimmed.endsWith(":")
        when {
            startsWithColon && endsWithColon -> TableAlignment.CENTER
            endsWithColon -> TableAlignment.RIGHT
            else -> TableAlignment.LEFT
        }
    }
}

fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val lines = text.split("\n")
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        
        // 1. Code Block Rule
        if (trimmed.startsWith("```")) {
            val language = trimmed.substring(3).trim()
            val codeBuffer = StringBuilder()
            var j = i + 1
            var closed = false
            while (j < lines.size) {
                val nLine = lines[j]
                if (nLine.trim() == "```") {
                    closed = true
                    j++
                    break
                } else {
                    codeBuffer.append(nLine)
                    if (j + 1 < lines.size && lines[j + 1].trim() != "```") {
                        codeBuffer.append("\n")
                    }
                    j++
                }
            }
            blocks.add(MarkdownBlock.CodeBlock(language, codeBuffer.toString()))
            i = j
            continue
        }

        // 2. Divider Rule
        if ((trimmed == "---" || trimmed == "***" || trimmed == "___" || 
             (trimmed.length >= 3 && trimmed.all { it == '-' }) || 
             (trimmed.length >= 3 && trimmed.all { it == '*' }) || 
             (trimmed.length >= 3 && trimmed.all { it == '_' })) && trimmed.isNotEmpty()) {
            blocks.add(MarkdownBlock.Divider)
            i++
            continue
        }
        
        // 3. Table Rule
        if (i + 1 < lines.size && isSeparatorRow(lines[i + 1])) {
            val headerLine = lines[i]
            val separatorLine = lines[i + 1]
            val headers = parseTableRow(headerLine)
            val alignments = parseTableAlignments(separatorLine)
            val rows = mutableListOf<List<String>>()
            
            var j = i + 2
            while (j < lines.size) {
                val rLine = lines[j]
                val rTrimmed = rLine.trim()
                if (rTrimmed.contains("|") && !isSeparatorRow(rLine) && rTrimmed.isNotEmpty()) {
                    rows.add(parseTableRow(rLine))
                    j++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.Table(headers, alignments, rows))
            i = j
            continue
        }
        
        // 4. Normal Text Block Rule
        val textBuffer = StringBuilder()
        textBuffer.append(line)
        var j = i + 1
        while (j < lines.size) {
            val nLine = lines[j]
            val nTrimmed = nLine.trim()
            val isNextCodeBlock = nTrimmed.startsWith("```")
            val isNextDivider = (nTrimmed == "---" || nTrimmed == "***" || nTrimmed == "___" || 
                                 (nTrimmed.length >= 3 && nTrimmed.all { it == '-' }) || 
                                 (nTrimmed.length >= 3 && nTrimmed.all { it == '*' }) || 
                                 (nTrimmed.length >= 3 && nTrimmed.all { it == '_' })) && nTrimmed.isNotEmpty()
            val isNextTable = j + 1 < lines.size && isSeparatorRow(lines[j + 1])
            
            if (isNextCodeBlock || isNextDivider || isNextTable) {
                break
            } else {
                textBuffer.append("\n").append(nLine)
                j++
            }
        }
        blocks.add(MarkdownBlock.Text(textBuffer.toString()))
        i = j
    }
    return blocks
}

@Composable
fun MarkdownTable(
    headers: List<String>,
    alignments: List<TableAlignment>,
    rows: List<List<String>>,
    contentColor: Color
) {
    val numCols = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
    if (numCols == 0) return

    val colWidths = remember(headers, rows) {
        val widths = MutableList(numCols) { 100.dp }
        for (colIdx in 0 until numCols) {
            val headerText = headers.getOrNull(colIdx) ?: ""
            var maxLen = headerText.length
            for (row in rows) {
                val cellText = row.getOrNull(colIdx) ?: ""
                if (cellText.length > maxLen) {
                    maxLen = cellText.length
                }
            }
            val estimatedWidth = (maxLen * 8 + 32).coerceIn(100, 300).dp
            widths[colIdx] = estimatedWidth
        }
        widths
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                // Header Row
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(vertical = 8.dp)
                ) {
                    for (colIdx in 0 until numCols) {
                        val headerText = headers.getOrNull(colIdx) ?: ""
                        val alignment = alignments.getOrNull(colIdx) ?: TableAlignment.LEFT
                        val horizontalAlignment = when (alignment) {
                            TableAlignment.LEFT -> Alignment.CenterStart
                            TableAlignment.CENTER -> Alignment.Center
                            TableAlignment.RIGHT -> Alignment.CenterEnd
                        }
                        val textAlign = when (alignment) {
                            TableAlignment.LEFT -> TextAlign.Start
                            TableAlignment.CENTER -> TextAlign.Center
                            TableAlignment.RIGHT -> TextAlign.End
                        }

                        Box(
                            modifier = Modifier
                                .width(colWidths[colIdx])
                                .padding(horizontal = 12.dp),
                            contentAlignment = horizontalAlignment
                        ) {
                            Text(
                                text = headerText,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = textAlign
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                // Data Rows
                rows.forEachIndexed { rowIdx, row ->
                    val isEven = rowIdx % 2 == 0
                    val rowBg = if (isEven) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f)
                    
                    Row(
                        modifier = Modifier
                            .background(rowBg)
                            .padding(vertical = 8.dp)
                    ) {
                        for (colIdx in 0 until numCols) {
                            val cellText = row.getOrNull(colIdx) ?: ""
                            val alignment = alignments.getOrNull(colIdx) ?: TableAlignment.LEFT
                            val horizontalAlignment = when (alignment) {
                                TableAlignment.LEFT -> Alignment.CenterStart
                                TableAlignment.CENTER -> Alignment.Center
                                TableAlignment.RIGHT -> Alignment.CenterEnd
                            }
                            val textAlign = when (alignment) {
                                TableAlignment.LEFT -> TextAlign.Start
                                TableAlignment.CENTER -> TextAlign.Center
                                TableAlignment.RIGHT -> TextAlign.End
                            }

                            Box(
                                modifier = Modifier
                                    .width(colWidths[colIdx])
                                    .padding(horizontal = 12.dp),
                                contentAlignment = horizontalAlignment
                            ) {
                                MarkdownTextInline(
                                    text = cellText,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    textAlign = textAlign
                                )
                            }
                        }
                    }
                    if (rowIdx < rows.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    }
                }
            }
        }
    }
}

@Composable
fun MarkdownTextInline(text: String, color: Color, textAlign: TextAlign, modifier: Modifier = Modifier) {
    val annotatedString = remember(text) {
        parseInlineStyles(text)
    }
    Text(
        text = annotatedString,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        textAlign = textAlign,
        modifier = modifier
    )
}

@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) {
        parseMarkdownBlocks(text)
    }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = color.copy(alpha = 0.2f),
                        thickness = 1.dp
                    )
                }
                is MarkdownBlock.Table -> {
                    MarkdownTable(
                        headers = block.headers,
                        alignments = block.alignments,
                        rows = block.rows,
                        contentColor = color
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (block.language.isNotEmpty()) {
                                Text(
                                    text = block.language.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.LightGray.copy(alpha = 0.8f)
                                    ),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                            Text(
                                text = block.content,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF64FFDA)
                                )
                            )
                        }
                    }
                }
                is MarkdownBlock.Text -> {
                    val annotatedString = remember(block.content) {
                        parseMarkdown(block.content)
                    }
                    Text(
                        text = annotatedString,
                        color = color,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

fun parseMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        lines.forEachIndexed { lineIdx, line ->
            // Match headers
            if (line.startsWith("# ")) {
                withStyle(style = SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)) {
                    append(parseInlineStyles(line.removePrefix("# ")))
                }
            } else if (line.startsWith("## ")) {
                withStyle(style = SpanStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)) {
                    append(parseInlineStyles(line.removePrefix("## ")))
                }
            } else if (line.startsWith("### ")) {
                withStyle(style = SpanStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)) {
                    append(parseInlineStyles(line.removePrefix("### ")))
                }
            } else if (line.startsWith("* ") || line.startsWith("- ")) {
                append("• ")
                append(parseInlineStyles(line.substring(2)))
            } else {
                append(parseInlineStyles(line))
            }
            if (lineIdx < lines.size - 1) {
                append("\n")
            }
        }
    }
}

fun parseInlineStyles(text: String): AnnotatedString {
    return buildAnnotatedString {
        var currentText = text
        while (currentText.isNotEmpty()) {
            val boldStart = currentText.indexOf("**")
            val italicStart = currentText.indexOf("*")
            val codeStart = currentText.indexOf("`")

            // Find first style tag
            val matches = listOf(
                Triple("bold", boldStart, 2),
                Triple("italic", italicStart, 1),
                Triple("code", codeStart, 1)
            ).filter { it.second != -1 }.sortedBy { it.second }

            if (matches.isEmpty()) {
                append(currentText)
                break
            }

            val (styleType, startIdx, tagLength) = matches.first()
            if (startIdx > 0) {
                append(currentText.substring(0, startIdx))
            }

            val remainingText = currentText.substring(startIdx + tagLength)
            val tagString = if (styleType == "bold") "**" else if (styleType == "italic") "*" else "`"
            val endIdx = remainingText.indexOf(tagString)

            if (endIdx == -1) {
                // Unclosed tag, treat as plain text
                append(tagString)
                currentText = remainingText
            } else {
                val styledValue = remainingText.substring(0, endIdx)
                when (styleType) {
                    "bold" -> withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(styledValue)
                    }
                    "italic" -> withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(styledValue)
                    }
                    "code" -> withStyle(
                        style = SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.Gray.copy(alpha = 0.2f),
                            color = Color(0xFF2E7D32)
                        )
                    ) {
                        append(styledValue)
                    }
                }
                currentText = remainingText.substring(endIdx + tagLength)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBar(
    courses: List<Course>,
    selectedCourseId: Int?,
    onCourseSelected: (Int?) -> Unit,
    selectedType: String?,
    onTypeSelected: (String?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1: Courses
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = selectedCourseId == null,
                    onClick = { onCourseSelected(null) },
                    label = { Text("All Subjects 📚") },
                    modifier = Modifier.testTag("filter_course_all")
                )
            }
            items(courses) { course ->
                FilterChip(
                    selected = selectedCourseId == course.moodleCourseId,
                    onClick = { onCourseSelected(course.moodleCourseId) },
                    label = { Text(course.shortName.ifEmpty { course.fullName }) },
                    modifier = Modifier.testTag("filter_course_${course.moodleCourseId}")
                )
            }
        }

        // Row 2: Types
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = selectedType == null,
                    onClick = { onTypeSelected(null) },
                    label = { Text("All Types ⚙️") },
                    modifier = Modifier.testTag("filter_type_all")
                )
            }
            
            val types = listOf(
                Pair("assign", "Assignments 📝"),
                Pair("quiz", "Quizzes 🧠"),
                Pair("forum", "Forums 💬")
            )
            
            items(types) { (typeKey, label) ->
                FilterChip(
                    selected = selectedType == typeKey,
                    onClick = { onTypeSelected(typeKey) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("filter_type_$typeKey")
                )
            }
        }
    }
}
