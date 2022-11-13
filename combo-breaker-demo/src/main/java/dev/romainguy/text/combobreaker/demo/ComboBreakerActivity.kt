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

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toOffset
import dev.romainguy.text.combobreaker.FlowType
import dev.romainguy.text.combobreaker.TextFlow
import dev.romainguy.text.combobreaker.demo.ui.theme.ComboBreakerTheme
import dev.romainguy.text.combobreaker.toContour

//region Sample text
const val SampleText = """The correctness and coherence of the lighting environment is paramount to achieving plausible visuals. After surveying existing rendering engines (such as Unity or Unreal Engine 4) as well as the traditional real-time rendering literature, it is obvious that coherency is rarely achieved.

The Unreal Engine, for instance, lets artists specify the “brightness” of a point light in lumen, a unit of luminous power. The brightness of directional lights is however expressed using an arbitrary unnamed unit. To match the brightness of a point light with a luminous power of 5,000 lumen, the artist must use a directional light of brightness 10. This kind of mismatch makes it difficult for artists to maintain the visual integrity of a scene when adding, removing or modifying lights. Using solely arbitrary units is a coherent solution but it makes reusing lighting rigs a difficult task. For instance, an outdoor scene will use a directional light of brightness 10 as the sun and all other lights will be defined relative to that value. Moving these lights to an indoor environment would make them too bright.

Our goal is therefore to make all lighting correct by default, while giving artists enough freedom to achieve the desired look. We will support a number of lights, split in two categories, direct and indirect lighting:

Deferred rendering is used by many modern 3D rendering engines to easily support dozens, hundreds or even thousands of light source (amongst other benefits). This method is unfortunately very expensive in terms of bandwidth. With our default PBR material model, our G-buffer would use between 160 and 192 bits per pixel, which would translate directly to rather high bandwidth requirements. Forward rendering methods on the other hand have historically been bad at handling multiple lights. A common implementation is to render the scene multiple times, once per visible light, and to blend (add) the results. Another technique consists in assigning a fixed maximum of lights to each object in the scene. This is however impractical when objects occupy a vast amount of space in the world (building, road, etc.).

Tiled shading can be applied to both forward and deferred rendering methods."""
//endregion

class ComboBreakerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val bitmap1 = remember { BitmapFactory.decodeResource(resources, R.drawable.microphone) }
            val bitmap2 = remember { BitmapFactory.decodeResource(resources, R.drawable.badge) }
            val bitmap3 = remember { BitmapFactory.decodeResource(resources, R.drawable.landscape1) }
            val bitmap4 = remember { BitmapFactory.decodeResource(resources, R.drawable.landscape2) }

            ComboBreakerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    val showDebugOverlay = remember { mutableStateOf(true) }

                    TextFlow(
                        SampleText,
                        modifier = Modifier.padding(16.dp),
                        style = TextStyle(fontSize = 14.sp),
                        debugOverlay = showDebugOverlay.value
                    ) {
//                        Image(
//                            bitmap = bitmap3.asImageBitmap(),
//                            contentDescription = "",
//                            modifier = Modifier.flowShape(margin = 8.dp)
//                        )
//                        Image(
//                            bitmap = bitmap4.asImageBitmap(),
//                            contentDescription = "",
//                            modifier = Modifier
//                                .align(Alignment.Center)
//                                .flowShape(margin = 8.dp)
//                        )
                        val offset1 = Offset(-bitmap1.width / 4.5f, 0.0f)
                        val offset2 = Offset(0.0f, -bitmap2.height / 3.0f)

                        Image(
                            bitmap = bitmap1.asImageBitmap(),
                            contentDescription = "",
                            modifier = Modifier
                                .offset { offset1.round() }
                                .flowShape(10.dp, FlowType.Right) { p, _, _ ->
                                    bitmap1
                                        .toContour(alphaThreshold = 0.1f)
                                        .asComposePath()
                                        .apply {
                                            translate(p.toOffset() + offset1)
                                        }
                                }
                        )

                        Image(
                            bitmap = bitmap2.asImageBitmap(),
                            contentDescription = "",
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset { offset2.round() }
                                .flowShape(10.dp, FlowType.Both) { p, _, _ ->
                                    bitmap2
                                        .toContour()
                                        .asComposePath()
                                        .apply {
                                            translate(p.toOffset() + offset2)
                                        }
                                }
                        )

                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .height(40.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = showDebugOverlay.value,
                                onCheckedChange = { showDebugOverlay.value = it },
                            )
                            Text(text = "Debug overlay")
                        }
                    }
                }
            }
        }
    }
}
