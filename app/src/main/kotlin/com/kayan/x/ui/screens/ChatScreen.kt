package com.kayan.x.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayan.x.agent.AgentStep
import com.kayan.x.safety.ConfirmationPolicy
import com.kayan.x.ui.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: MainViewModel) {
    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    // Auto-scroll to latest message
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty())
            listState.animateScrollToItem(state.messages.size - 1)
    }

    // Confirmation dialog
    state.pendingConfirmation?.let { pending ->
        ConfirmationDialog(
            toolName  = pending.toolName,
            params    = pending.params,
            riskLevel = pending.riskLevel,
            onConfirm = pending.onConfirm,
            onDeny    = pending.onDeny
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kayan X", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    if (state.agentRunning) {
                        IconButton(onClick = { vm.cancelAgent() }) {
                            Icon(Icons.Default.Close, "إيقاف", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(onClick = { vm.clearChat() }) {
                        Icon(Icons.Default.Delete, "مسح")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            ChatInputBar(
                value    = input,
                enabled  = !state.agentRunning && state.modelLoadState is MainViewModel.ModelLoadState.Loaded,
                isRunning = state.agentRunning,
                onValueChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        vm.sendMessage(input.trim())
                        input = ""
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state         = listState,
            modifier      = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Model status banner
            item {
                ModelStatusBanner(state.modelLoadState)
            }
            items(state.messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
            if (state.agentRunning) {
                item {
                    ThinkingIndicator()
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModelStatusBanner(loadState: MainViewModel.ModelLoadState) {
    val (text, color) = when (loadState) {
        is MainViewModel.ModelLoadState.NotLoaded  ->
            "لم يُحمَّل أي نموذج" to MaterialTheme.colorScheme.error
        is MainViewModel.ModelLoadState.Loading    ->
            "جارٍ تحميل ${loadState.modelName}…" to MaterialTheme.colorScheme.secondary
        is MainViewModel.ModelLoadState.Loaded     ->
            "✓ ${loadState.modelName} (${loadState.loadMs}ms)" to MaterialTheme.colorScheme.primary
        is MainViewModel.ModelLoadState.Failed     ->
            "✗ فشل التحميل: ${loadState.error}" to MaterialTheme.colorScheme.error
    }
    Surface(
        shape  = RoundedCornerShape(8.dp),
        color  = color.copy(alpha = 0.1f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text     = text,
            color    = color,
            style    = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun MessageBubble(msg: MainViewModel.ChatMessage) {
    val isUser   = msg.role == MainViewModel.ChatMessage.Role.USER
    val isSystem = msg.role == MainViewModel.ChatMessage.Role.SYSTEM
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier          = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isSystem) {
            // System messages: monospace, muted, full width
            Text(
                text  = msg.content,
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            )
            return@Column
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd   = if (isUser) 4.dp  else 16.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp
            ),
            color = if (isUser)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text  = msg.content,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                            else        MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
                // Show agent steps expander
                if (msg.agentSteps.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) "▲ إخفاء الخطوات (${msg.agentSteps.size})"
                            else          "▼ عرض الخطوات (${msg.agentSteps.size})",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (expanded) {
                        msg.agentSteps.forEach { step ->
                            AgentStepCard(step)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentStepCard(step: AgentStep) {
    val verPassed = step.verification?.passed ?: true
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (verPassed)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (step.success) Icons.Default.Check else Icons.Default.Warning,
                    contentDescription = null,
                    tint   = if (step.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = "${step.stepId}: ${step.toolName}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text  = "${step.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                )
            }
            if (!step.success && step.error != null) {
                Text(
                    text  = step.error,
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            step.verification?.let { v ->
                Text(
                    text  = "Verify: $v",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (v.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier  = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color     = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text("Kayan يفكر…", style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    enabled: Boolean,
    isRunning: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        modifier       = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value         = value,
                onValueChange = onValueChange,
                enabled       = enabled,
                placeholder   = { Text(if (enabled) "أرسل مهمة لـ Kayan…" else "حمّل نموذجًا أولًا") },
                modifier      = Modifier.weight(1f),
                maxLines      = 4,
                shape         = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick  = onSend,
                enabled  = enabled && value.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "إرسال")
            }
        }
    }
}

@Composable
private fun ConfirmationDialog(
    toolName: String,
    params: Map<String, Any>,
    riskLevel: ConfirmationPolicy.OperationRisk,
    onConfirm: () -> Unit,
    onDeny: () -> Unit
) {
    val riskColor = when (riskLevel) {
        ConfirmationPolicy.OperationRisk.CRITICAL -> MaterialTheme.colorScheme.error
        ConfirmationPolicy.OperationRisk.HIGH     -> Color(0xFFF97316)
        else                                       -> MaterialTheme.colorScheme.primary
    }
    AlertDialog(
        onDismissRequest = onDeny,
        icon             = { Icon(Icons.Default.Warning, null, tint = riskColor) },
        title            = { Text("تأكيد عملية $toolName") },
        text             = {
            Column {
                Text("مستوى الخطر: ${riskLevel.name}", color = riskColor,
                     style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                Text("المعاملات:", style = MaterialTheme.typography.bodySmall)
                params.forEach { (k, v) ->
                    Text(
                        text  = "  $k: $v",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors  = ButtonDefaults.buttonColors(containerColor = riskColor)
            ) { Text("تأكيد التنفيذ") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDeny) { Text("إلغاء") }
        }
    )
}
