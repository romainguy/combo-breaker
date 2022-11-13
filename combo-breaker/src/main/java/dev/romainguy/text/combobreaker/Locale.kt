/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.romainguy.text.combobreaker

import android.text.TextPaint
import android.text.style.LocaleSpan
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList

internal fun Locale.toJavaLocale(): java.util.Locale = java.util.Locale(language, region)

/**
 * This class is here to ensure that the classes that use this API will get verified and can be
 * AOT compiled. It is expected that this class will soft-fail verification, but the classes
 * which use this method will pass.
 */
@RequiresApi(24)
internal object LocaleListHelperMethods {
    @RequiresApi(24)
    @DoNotInline
    fun localeSpan(localeList: LocaleList): Any =
        LocaleSpan(
            android.os.LocaleList(*localeList.map { it.toJavaLocale() }.toTypedArray())
        )

    @RequiresApi(24)
    @DoNotInline
    fun setTextLocales(textPaint: TextPaint, localeList: LocaleList) {
        textPaint.textLocales = android.os.LocaleList(
            *localeList.map { it.toJavaLocale() }.toTypedArray()
        )
    }
}
