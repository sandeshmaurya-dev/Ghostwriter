package com.musheer360.swiftslate.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * @param contentPadding inner padding. Defaults to the shared [SlateRhythm] so every
 *   card on every tab agrees; pass a value only to deliberately deviate.
 * @param verticalArrangement how children are distributed. Only meaningful together
 *   with [fillHeight].
 */
@Composable
fun SlateCard(
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    contentPadding: Dp = LocalSlateRhythm.current.cardPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // A 1px rim is what separates the card from the deep background in the
            // dark palette — pure elevation reads flat on near-black surfaces.
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

/**
 * The page heading. Size and the gap below it come from the shared [SlateRhythm], so
 * all four tabs start at the same baseline and their first cards line up. The optional
 * [subtitle] renders one muted line below, like the reference design's lead-in.
 */
@Composable
fun ScreenTitle(
    title: String,
    fontSize: TextUnit = LocalSlateRhythm.current.titleSize,
    bottomPadding: Dp = LocalSlateRhythm.current.titleGap,
    subtitle: String? = null
) {
    Column(modifier = Modifier.padding(bottom = bottomPadding)) {
        Text(
            text = title,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = LocalSlateRhythm.current.bodySize,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SlateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        readOnly = readOnly,
        isError = isError,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        )
    )
}

@Composable
fun SlateDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
fun SlateItemCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = LocalSlateRhythm.current.itemPadding,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            content = content
        )
    }
}
