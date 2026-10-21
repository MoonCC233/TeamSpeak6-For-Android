package dev.tsdroid.pad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.tsdroid.pad.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val avatarGradients = listOf(
    Color(0xFF1D4F49) to Color(0xFF3E9E8F),
    Color(0xFF2B4166) to Color(0xFF5A83C4),
    Color(0xFF4B3557) to Color(0xFF9469B0),
    Color(0xFF4D3A28) to Color(0xFFB58851),
    Color(0xFF234B35) to Color(0xFF5BA66F),
    Color(0xFF4B2F38) to Color(0xFFB2687A),
    Color(0xFF243F46) to Color(0xFF5C97A3),
    Color(0xFF3F3A24) to Color(0xFFA99A4F),
)

fun initialsOf(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    return trimmed.substring(0, 1).uppercase(Locale.getDefault())
}

fun formatClock(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
fun PadLogo(size: Dp = 42.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 30))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2F7C72), PadColors.Accent),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Headphones,
            contentDescription = null,
            tint = PadColors.OnAccent,
            modifier = Modifier.size(size * 0.56f),
        )
    }
}

@Composable
fun PadAvatar(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    image: ImageBitmap? = null,
    speaking: Boolean = false,
    online: Boolean = true,
    presenceColor: Color = PadColors.Green,
    showPresence: Boolean = false,
) {
    val shape = RoundedCornerShape(percent = 30)
    val index = remember(name) { abs(name.hashCode()) % avatarGradients.size }
    val gradient = remember(index) {
        val (start, end) = avatarGradients[index]
        Brush.linearGradient(listOf(start, end))
    }
    val fontSize = with(LocalDensity.current) { (size.toPx() * 0.34f).toSp() }

    Box(modifier.size(size)) {
        Box(
            modifier
                .fillMaxSize()
                .clip(shape)
                .background(if (image == null) gradient else Brush.linearGradient(listOf(PadColors.SurfaceHigh, PadColors.SurfaceHigh)))
                .then(
                    if (speaking) Modifier.border(2.dp, PadColors.Green, shape) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = initialsOf(name),
                    color = Color.White,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (showPresence) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.3f)
                    .clip(CircleShape)
                    .background(if (online) presenceColor else PadColors.Muted)
                    .border(2.dp, PadColors.Surface, CircleShape),
            )
        }
    }
}

@Composable
fun PadCard(
    modifier: Modifier = Modifier,
    color: Color = PadColors.Surface,
    borderColor: Color = PadColors.Border,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(color)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp)),
    ) {
        content()
    }
}

@Composable
fun SectionKicker(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = PadColors.Accent,
        letterSpacing = 1.8.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.32f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * A small live marker used both in the member list and on video cards. The
 * dark variant is for overlaying the video surface; the tinted variant matches
 * the rest of the Pad chrome.
 */
@Composable
fun PadLiveBadge(
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    dark: Boolean = false,
) {
    val accent = PadColors.Red
    val background = if (dark) Color.Black.copy(alpha = 0.55f) else accent.copy(alpha = 0.14f)
    val foreground = if (dark) Color.White else accent
    Row(
        modifier
            .clip(CircleShape)
            .background(background)
            .then(
                if (dark) Modifier else Modifier.border(
                    1.dp,
                    accent.copy(alpha = 0.32f),
                    CircleShape,
                ),
            )
            .padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 1.dp else 3.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (compact) 4.dp else 6.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.width(if (compact) 4.dp else 5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
    }
}

/**
 * Activity resume state for media previews. Screen-share viewers should release
 * their peer connection when the Pad is in the background, just like the phone
 * client, and rejoin when the user comes back.
 */
@Composable
fun rememberPadResumed(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

@Composable
fun PadIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = PadColors.Muted,
    background: Color = Color.Transparent,
    size: Dp = 38.dp,
    iconSize: Dp = 18.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * A WebSpeak-style input: external label, rounded filled surface, accent focus
 * ring. Material's OutlinedTextField floats its label and adds more vertical
 * chrome than the reference UI, so this is built on BasicTextField.
 */
@Composable
fun PadInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    isPassword: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
    required: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) PadColors.Accent else PadColors.Muted,
                letterSpacing = 0.3.sp,
            )
            if (required) {
                Text(
                    text = " *",
                    style = MaterialTheme.typography.labelSmall,
                    color = PadColors.Accent,
                )
            } else {
                Text(
                    text = "  " + stringResource(R.string.field_optional),
                    style = MaterialTheme.typography.labelSmall,
                    color = PadColors.Muted.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(shape)
                .background(PadColors.SurfaceAlt)
                .border(1.dp, if (focused) PadColors.Accent else PadColors.Border, shape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = if (focused) PadColors.Accent else PadColors.Muted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PadColors.Muted.copy(alpha = 0.58f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = PadColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(PadColors.Accent),
                    visualTransformation = if (isPassword) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun PadAmbientBackground(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(PadColors.Page)
        drawRect(
            Brush.radialGradient(
                colors = listOf(PadColors.Accent.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.82f, size.height * 0.02f),
                radius = size.width * 0.62f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(PadColors.Green.copy(alpha = 0.06f), Color.Transparent),
                center = Offset(size.width * 0.06f, size.height * 1.02f),
                radius = size.width * 0.58f,
            ),
        )
        val step = 96.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = PadColors.Border.copy(alpha = 0.08f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f,
            )
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = PadColors.Border.copy(alpha = 0.06f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            y += step
        }
    }
}

@Composable
fun EmptyHint(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(PadColors.AccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = PadColors.Accent, modifier = Modifier.size(20.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = PadColors.Text,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = PadColors.Muted,
            textAlign = TextAlign.Center,
        )
    }
}
