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

package dev.romainguy.text.combobreaker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Material 3 color schemes
private val comboBreakerDarkColorScheme = darkColorScheme(
    primary = comboBreakerDarkPrimary,
    onPrimary = comboBreakerDarkOnPrimary,
    primaryContainer = comboBreakerDarkPrimaryContainer,
    onPrimaryContainer = comboBreakerDarkOnPrimaryContainer,
    inversePrimary = comboBreakerDarkPrimaryInverse,
    secondary = comboBreakerDarkSecondary,
    onSecondary = comboBreakerDarkOnSecondary,
    secondaryContainer = comboBreakerDarkSecondaryContainer,
    onSecondaryContainer = comboBreakerDarkOnSecondaryContainer,
    tertiary = comboBreakerDarkTertiary,
    onTertiary = comboBreakerDarkOnTertiary,
    tertiaryContainer = comboBreakerDarkTertiaryContainer,
    onTertiaryContainer = comboBreakerDarkOnTertiaryContainer,
    error = comboBreakerDarkError,
    onError = comboBreakerDarkOnError,
    errorContainer = comboBreakerDarkErrorContainer,
    onErrorContainer = comboBreakerDarkOnErrorContainer,
    background = comboBreakerDarkBackground,
    onBackground = comboBreakerDarkOnBackground,
    surface = comboBreakerDarkSurface,
    onSurface = comboBreakerDarkOnSurface,
    inverseSurface = comboBreakerDarkInverseSurface,
    inverseOnSurface = comboBreakerDarkInverseOnSurface,
    surfaceVariant = comboBreakerDarkSurfaceVariant,
    onSurfaceVariant = comboBreakerDarkOnSurfaceVariant,
    outline = comboBreakerDarkOutline
)

private val comboBreakerLightColorScheme = lightColorScheme(
    primary = comboBreakerLightPrimary,
    onPrimary = comboBreakerLightOnPrimary,
    primaryContainer = comboBreakerLightPrimaryContainer,
    onPrimaryContainer = comboBreakerLightOnPrimaryContainer,
    inversePrimary = comboBreakerLightPrimaryInverse,
    secondary = comboBreakerLightSecondary,
    onSecondary = comboBreakerLightOnSecondary,
    secondaryContainer = comboBreakerLightSecondaryContainer,
    onSecondaryContainer = comboBreakerLightOnSecondaryContainer,
    tertiary = comboBreakerLightTertiary,
    onTertiary = comboBreakerLightOnTertiary,
    tertiaryContainer = comboBreakerLightTertiaryContainer,
    onTertiaryContainer = comboBreakerLightOnTertiaryContainer,
    error = comboBreakerLightError,
    onError = comboBreakerLightOnError,
    errorContainer = comboBreakerLightErrorContainer,
    onErrorContainer = comboBreakerLightOnErrorContainer,
    background = comboBreakerLightBackground,
    onBackground = comboBreakerLightOnBackground,
    surface = comboBreakerLightSurface,
    onSurface = comboBreakerLightOnSurface,
    inverseSurface = comboBreakerLightInverseSurface,
    inverseOnSurface = comboBreakerLightInverseOnSurface,
    surfaceVariant = comboBreakerLightSurfaceVariant,
    onSurfaceVariant = comboBreakerLightOnSurfaceVariant,
    outline = comboBreakerLightOutline
)

@Composable
fun ComboBreakerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val comboBreakerColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> comboBreakerDarkColorScheme
        else -> comboBreakerLightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = comboBreakerColorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkTheme
        }
    }

    MaterialTheme(
        colorScheme = comboBreakerColorScheme,
        typography = comboBreakerTypography,
        shapes = comboBreakerShapes,
        content = content
    )
}
