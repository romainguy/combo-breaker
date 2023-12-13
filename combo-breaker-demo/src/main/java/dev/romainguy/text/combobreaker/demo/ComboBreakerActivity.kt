/*
 * Copyright (C) 2022 Romain Guy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.romainguy.text.combobreaker.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import dev.romainguy.text.combobreaker.FlowType
import dev.romainguy.text.combobreaker.TextFlowHyphenation
import dev.romainguy.text.combobreaker.TextFlowJustification
import dev.romainguy.text.combobreaker.demo.ui.theme.ComboBreakerTheme
import dev.romainguy.text.combobreaker.material3.TextFlow
import dev.romainguy.graphics.path.toPath
import dev.romainguy.graphics.path.toPaths

class ComboBreakerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialDemoSetup {
                TextFlowDemo()
            }
        }
    }

    @Composable
    private fun TextFlowDemo() {
        val colorScheme = MaterialTheme.colorScheme

        var columns by remember { mutableStateOf(2) }
        var useMultipleShapes by remember { mutableStateOf(false) }
        var useRectangleShapes by remember { mutableStateOf(true) }
        var isJustified by remember { mutableStateOf(false) }
        var isHyphenated by remember { mutableStateOf(true) }
        var isDebugOverlayEnabled by remember { mutableStateOf(true) }

        //region Sample text
        val sampleText by remember { derivedStateOf {
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp)) {
                    if (useMultipleShapes || !useRectangleShapes) {
                        append("T")
                    }
                    append("he Hyphen")
                }
                append("\n\n")
                append("The ")
                withStyle(style = SpanStyle(color = colorScheme.primary)) {
                    append("English language ")
                }
                append("does not have definitive hyphenation rules, though various ")
                withStyle(style = SpanStyle(color = colorScheme.primary)) {
                    append("style guides ")
                }
                append("provide detailed usage recommendations and have a significant ")
                append("amount of overlap in what they advise. Hyphens are mostly used to break single ")
                append("words into parts or to join ordinarily separate words into single words. Spaces ")
                append("are not placed between a hyphen and either of the elements it connects except ")
                append("when using a suspended or ")
                withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                    append("\"hanging\" ")
                }
                append("hyphen that stands in for a repeated word (e.g., nineteenth- and ")
                append("twentieth-century writers). Style conventions that apply to hyphens (and ")
                append("dashes) have evolved to support ease of reading in complex constructions; ")
                append("editors often accept deviations if they aid rather than hinder easy ")
                append("comprehension.\n\n")

                append("The use of the hyphen in ")
                withStyle(style = SpanStyle(color = colorScheme.primary)) {
                    append("English compound ")
                }
                append("nouns and verbs has, in general, ")
                append("been steadily declining. Compounds that might once have been hyphenated are ")
                append("increasingly left with spaces or are combined into one word. Reflecting this ")
                append("changing usage, in 2007, the sixth edition of the ")
                withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                    append("Shorter Oxford English ")
                }
                append("Dictionary removed the hyphens from 16,000 entries, such as fig-leaf (now fig ")
                append("leaf), pot-belly (now pot belly), and pigeon-hole (now pigeonhole). The ")
                append("increasing prevalence of computer technology and the advent of the Internet ")
                append("have given rise to a subset of common nouns that might have been hyphenated ")
                append("in the past (e.g., toolbar, hyperlink, and pastebin).\n\n")

                append("Despite decreased use, hyphenation remains the norm in certain ")
                append("compound-modifier constructions and, among some authors, with certain ")
                append("prefixes (see below). Hyphenation is also routinely used as part of ")
                append("syllabification in justified texts to avoid unsightly spacing (especially ")
                append("in columns with narrow line lengths, as when used with newspapers).")
            }
        } }
        //endregion

        val microphone = remember {
            BitmapFactory.decodeResource(resources, R.drawable.microphone).let {
                Bitmap.createScaledBitmap(it, it.width / 2, it.height / 2, true)
            }
        }
        val microphoneShape by remember {
            derivedStateOf { microphone.toPath(alphaThreshold = 0.5f).asComposePath() }
        }

        val badge = remember { BitmapFactory.decodeResource(resources, R.drawable.badge) }
        val badgeShape by remember {
            derivedStateOf { badge.toPath().asComposePath() }
        }

        val letterT = remember { BitmapFactory.decodeResource(resources, R.drawable.letter_t) }
        val landscape = remember { BitmapFactory.decodeResource(resources, R.drawable.landscape) }

        val hearts = remember { BitmapFactory.decodeResource(resources, R.drawable.hearts) }
        val heartsShape by remember {
            derivedStateOf {
                if (useMultipleShapes) {
                    hearts.toPaths().map { it.asComposePath() }
                } else {
                    emptyList()
                }
            }
        }

        val justification by remember {
            derivedStateOf {
                if (isJustified) {
                    TextFlowJustification.Auto
                } else {
                    TextFlowJustification.None
                }
            }
        }
        val hyphenation by remember {
            derivedStateOf {
                if (isHyphenated) {
                    TextFlowHyphenation.Auto
                } else {
                    TextFlowHyphenation.None
                }
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            TextFlow(
                sampleText,
                modifier = Modifier.weight(1.0f).fillMaxWidth(),
                style =  LocalTextStyle.current.merge(
                    TextStyle(
                        color = colorScheme.onSurface,
                        fontSize = if (useMultipleShapes) 13.sp else LocalTextStyle.current.fontSize
                    )
                ),
                justification = justification,
                hyphenation = hyphenation,
                columns = columns,
                debugOverlay = isDebugOverlayEnabled
            ) {
                if (!useMultipleShapes) {
                    Image(
                        bitmap = (if (useRectangleShapes) letterT else microphone).asImageBitmap(),
                        contentDescription = "",
                        modifier = Modifier
                            .offset {
                                if (useRectangleShapes)
                                    IntOffset(0, 0)
                                else
                                    Offset(-microphone.width / 4.5f, 0.0f).round()
                            }
                            .flowShape(
                                FlowType.OutsideEnd,
                                if (useRectangleShapes) 0.dp else 8.dp,
                                if (useRectangleShapes) null else microphoneShape
                            )
                    )

                    Image(
                        bitmap = (if (useRectangleShapes) landscape else badge).asImageBitmap(),
                        contentDescription = "",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .flowShape(
                                FlowType.Outside,
                                if (useRectangleShapes) 8.dp else 10.dp,
                                if (useRectangleShapes) null else badgeShape
                            )
                    )
                } else {
                    Image(
                        bitmap = hearts.asImageBitmap(),
                        contentDescription = "",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .flowShapes(FlowType.Outside, 2.dp, heartsShape)
                    )
                }
            }

            DemoControls(
                columns, { columns = it },
                useRectangleShapes, { useRectangleShapes = it },
                useMultipleShapes, { useMultipleShapes = it },
                isJustified, { isJustified = it},
                isHyphenated, { isHyphenated = it},
                isDebugOverlayEnabled, { isDebugOverlayEnabled = it},
            )
        }
    }

    @Composable
    private fun DemoControls(
        columns: Int,
        onColumnsChanged: (Int) -> Unit,
        useRectangleShapes: Boolean,
        onUseRectangleShapesChanged: (Boolean) -> Unit,
        useMultipleShapes: Boolean,
        onUseMultipleShapesChanged: (Boolean) -> Unit,
        justify: Boolean,
        onJustifyChanged: (Boolean) -> Unit,
        hyphenation: Boolean,
        onHyphenationChanged: (Boolean) -> Unit,
        debugOverlay: Boolean,
        onDebugOverlayChanged: (Boolean) -> Unit,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Columns")
            Spacer(modifier = Modifier.width(8.dp))
            Slider(
                modifier = Modifier.weight(0.1f),
                value = columns.toFloat(),
                onValueChange = { onColumnsChanged(it.toInt()) },
                valueRange = 1.0f..4.0f
            )
            Spacer(modifier = Modifier.width(8.dp))

            Checkbox(checked = useRectangleShapes, onCheckedChange = onUseRectangleShapesChanged)
            Text(text = "Rects")

            Checkbox(checked = useMultipleShapes, onCheckedChange = onUseMultipleShapesChanged)
            Text(text = "Multi")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = justify, onCheckedChange = onJustifyChanged)
            Text(text = "Justify")

            Checkbox(checked = hyphenation, onCheckedChange = onHyphenationChanged)
            Text(text = "Hyphenate")

            Checkbox(checked = debugOverlay, onCheckedChange = onDebugOverlayChanged)
            Text(text = "Debug")
        }
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun MaterialDemoSetup(content: @Composable () -> Unit) {
        ComboBreakerTheme {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Combo Breaker Demo") },
                        colors = TopAppBarDefaults.mediumTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            titleContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                },
            ) { padding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues = padding),
                    color = MaterialTheme.colorScheme.surface,
                    content = content
                )
            }
        }
    }
}
