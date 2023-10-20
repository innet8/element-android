/*
 * Copyright (c) 2023 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.onboarding

import android.content.Intent

object ServerConfigPasswordFormattingUtils {
    fun passwordFormatting(inputPassword: String = "000000000000"): String{
        var input = inputPassword
        input.trim()
        val targetLength = 16
        val paddingChar = '0'

        if (input.length < 16) {
            input = input.padEnd(targetLength, paddingChar)
        }
        return input
    }
}
