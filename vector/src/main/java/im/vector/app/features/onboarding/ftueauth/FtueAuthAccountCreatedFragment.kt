/*
 * Copyright (c) 2021 New Vector Ltd
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

package im.vector.app.features.onboarding.ftueauth

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.text.toSpannable
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.animations.play
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.utils.isAnimationEnabled
import im.vector.app.core.utils.styleMatchingText
import im.vector.app.databinding.FragmentFtueAccountCreatedBinding
import im.vector.app.features.onboarding.OnboardingAction
import im.vector.app.features.onboarding.OnboardingViewEvents
import im.vector.app.features.onboarding.OnboardingViewState
import im.vector.app.features.onboarding.SingleUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
class FtueAuthAccountCreatedFragment :
        AbstractFtueAuthFragment<FragmentFtueAccountCreatedBinding>() {

    private var hasPlayedConfetti = false
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFtueAccountCreatedBinding {
        return FragmentFtueAccountCreatedBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        val url = "${activeSessionHolder.getActiveSession().sessionParams.homeServerConnectionConfig.homeServerUriBase}_synapse/admin/v1/record_registration_token/${SingleUrl.inviteCode}"
        if (!SingleUrl.inviteCode.isNullOrEmpty()){recodeInvite(url)}

    }
    private fun recodeInvite(url: String){
        //val url: String = "${activeSessionHolder.getActiveSession().sessionParams.homeServerConnectionConfig.homeServerUriBase}_synapse/admin/v1/record_registration_token"
        //_synapse/admin/v1/record_registration_token
        val request = Request.Builder()
                .url(url)
                .addHeader("Authorization","Bearer ${activeSessionHolder.getActiveSession().sessionParams.credentials.accessToken}")
                //.method()方法与.get()方法选取1种即可
                .method("GET", null)
                .build()

        //创建call并调用enqueue()方法实现网络请求
        OkHttpClient().newCall(request)
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        println("error")
                    }
                    override fun onResponse(call: Call, response: Response) {
                        Timber.i("response=${response}")
                        val responseCode = response.code
                        Timber.i("responseCode=${responseCode}")
                        if (responseCode == 200 ){
                            val responseBody = response.body
                            Timber.i("responseBody=${responseBody}")
                            val jsonString: String? = responseBody?.string()
                            Timber.i("jsonString=${jsonString}")
                            SingleUrl.inviteCode = ""
                        }else{
                            println("错误-->")
                        }
                    }
                })
    }
    private fun setupViews() {
        views.accountCreatedPersonalize.debouncedClicks { viewModel.handle(OnboardingAction.PersonalizeProfile) }
        views.accountCreatedTakeMeHome.debouncedClicks { viewModel.handle(OnboardingAction.PostViewEvent(OnboardingViewEvents.OnTakeMeHome)) }
        views.accountCreatedTakeMeHomeCta.debouncedClicks { viewModel.handle(OnboardingAction.PostViewEvent(OnboardingViewEvents.OnTakeMeHome)) }
    }

    override fun updateWithState(state: OnboardingViewState) {
        val userId = state.personalizationState.userId
        val subtitle = getString(R.string.ftue_account_created_subtitle, userId).toSpannable().styleMatchingText(userId, Typeface.BOLD)
        views.accountCreatedSubtitle.text = subtitle
        val canPersonalize = state.personalizationState.supportsPersonalization()
        views.personalizeButtonGroup.isVisible = canPersonalize
        views.takeMeHomeButtonGroup.isVisible = !canPersonalize

        if (!hasPlayedConfetti && requireContext().isAnimationEnabled()) {
            hasPlayedConfetti = true
            views.viewKonfetti.isVisible = true
            views.viewKonfetti.play()
        }
    }

    override fun resetViewModel() {
        // Nothing to do
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        viewModel.handle(OnboardingAction.PostViewEvent(OnboardingViewEvents.OnTakeMeHome))
        return true
    }
}
