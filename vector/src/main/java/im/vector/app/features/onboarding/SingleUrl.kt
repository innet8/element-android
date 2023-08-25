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
import im.vector.app.core.di.ActiveSessionHolder

object SingleUrl {
    var isLinkOpen: Boolean = false
    var serviceUrl: String = ""
    var inviteCode: String = ""

    fun isLaunchedFromLink(intent: Intent): Boolean {
        val action = intent.action
        val data = intent.data
        // 检查Intent的动作和数据是否指示被链接启动
        return action == Intent.ACTION_VIEW && data != null
    }
}
