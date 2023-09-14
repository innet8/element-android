/*
 * Copyright 2019 New Vector Ltd
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

import android.content.Context
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.autofill.HintConstants
import androidx.core.view.isVisible
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.clearErrorOnChange
import im.vector.app.core.extensions.content
import im.vector.app.core.extensions.editText
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.hidePassword
import im.vector.app.core.extensions.realignPercentagesToParent
import im.vector.app.core.extensions.setOnFocusLostListener
import im.vector.app.core.extensions.setOnImeDoneListener
import im.vector.app.core.extensions.toReducedUrl
import im.vector.app.core.utils.openUrlInChromeCustomTab
import im.vector.app.core.utils.openUrlInExternalBrowser
import im.vector.app.databinding.FragmentFtueCombinedLoginBinding
import im.vector.app.features.VectorFeatures
import im.vector.app.features.login.LoginMode
import im.vector.app.features.login.SSORedirectRouterActivity
import im.vector.app.features.login.SocialLoginButtonsView
import im.vector.app.features.login.qr.QrCodeLoginArgs
import im.vector.app.features.login.qr.QrCodeLoginType
import im.vector.app.features.login.render
import im.vector.app.features.onboarding.AESCryptUtils
import im.vector.app.features.onboarding.MyFileUtils
import im.vector.app.features.onboarding.OnboardingAction
import im.vector.app.features.onboarding.OnboardingViewEvents
import im.vector.app.features.onboarding.OnboardingViewState
import im.vector.app.features.settings.VectorLocale
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import org.json.JSONObject
import org.matrix.android.sdk.api.auth.SSOAction
import reactivecircus.flowbinding.android.widget.textChanges
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class FtueAuthCombinedLoginFragment :
        AbstractSSOFtueAuthFragment<FragmentFtueCombinedLoginBinding>() {

    @Inject lateinit var loginFieldsValidation: LoginFieldsValidation
    @Inject lateinit var loginErrorParser: LoginErrorParser
    @Inject lateinit var vectorFeatures: VectorFeatures
    @Inject lateinit var languageVectorLocale: VectorLocale

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFtueCombinedLoginBinding {
        return FragmentFtueCombinedLoginBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSubmitButton()
        views.loginRoot.realignPercentagesToParent()
        views.editServerButton.debouncedClicks { viewModel.handle(OnboardingAction.PostViewEvent(OnboardingViewEvents.EditServerSelection)) }
        views.loginPasswordInput.setOnImeDoneListener { submit() }
        views.loginInput.setOnFocusLostListener(viewLifecycleOwner) {
            viewModel.handle(OnboardingAction.UserNameEnteredAction.Login(views.loginInput.content()))
        }
        views.loginForgotPassword.debouncedClicks { viewModel.handle(OnboardingAction.PostViewEvent(OnboardingViewEvents.OnForgetPasswordClicked)) }

        viewModel.onEach(OnboardingViewState::canLoginWithQrCode) {
            configureQrCodeLoginButtonVisibility(it)
        }
        views.loginLanguageSelect.text = languageVectorLocale.localeToLocalisedString(languageVectorLocale.applicationLocale)
        views.loginLanguage.debouncedClicks{
            viewModel.handle(OnboardingAction.PostViewEvent(OnboardingViewEvents.OnLanguageClicked))
        }
    }

    private fun loadingInfo(){
        var filePath = MyFileUtils.getFileDir(requireActivity()) +"/"+MyFileUtils.fileName
        var content = MyFileUtils.readFile(filePath)
        if (!content.isNullOrEmpty()){
            content.trim()
            if (content.length>5){
                showReadAlert(requireActivity(), content)
            }else{
                Toast.makeText(requireActivity(),"加载的文件内容为空", Toast.LENGTH_LONG).show()
            }
        }else{
            Toast.makeText(requireActivity(),"加载的文件不存在", Toast.LENGTH_LONG).show()
        }
    }
    private fun showReadAlert(context: Context, content: String){
        var builder = AlertDialog.Builder(context)
        builder.setTitle("提示")
        builder.setMessage("是否加载服务器和账号信息")
        val editText = EditText(context)
        editText.hint = "请设置解密口令"
        builder.setView(editText)
        builder.setCancelable(false)
        builder.setPositiveButton("确认",null)
        builder.setNegativeButton("取消",null)
        var dialogs: AlertDialog = builder.create()
        if (!dialogs.isShowing){
            dialogs.show()
        }
        dialogs.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener {
            var input = editText.text.toString()
            if (input.isNotEmpty()){
                if (input.length<16){
                    Toast.makeText(context,"最低长度为16",Toast.LENGTH_LONG).show()
                }else{
                    var redInfo = AESCryptUtils.decrypt(content,input)
                    if (redInfo.isEmpty()){
                        Toast.makeText(context,"解密口令错误",Toast.LENGTH_LONG).show()
                    }else{
                        val jsonObject = JSONObject(redInfo)
                        val serverUrl = jsonObject.getString("serverUrl")
                        val account = jsonObject.getString("account")
                        views.loginEditText.setText(account)
                        Timber.i("dddddd serverUrl =$serverUrl")
                        Timber.i("dddddd account =$account")
                        dialogs.cancel()
                    }
                }
            }else{
                Toast.makeText(context,"请设置解密口令",Toast.LENGTH_LONG).show()
            }
        })
        dialogs.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dialogs.cancel()
        }
    }
    private fun configureQrCodeLoginButtonVisibility(canLoginWithQrCode: Boolean) {
        views.loginWithQrCode.isVisible = canLoginWithQrCode
        if (canLoginWithQrCode) {
            views.loginWithQrCode.debouncedClicks {
                navigator
                        .openLoginWithQrCode(
                                requireActivity(),
                                QrCodeLoginArgs(
                                        loginType = QrCodeLoginType.LOGIN,
                                        showQrCodeImmediately = false,
                                )
                        )
            }
        }
    }

    private fun setupSubmitButton() {
        views.loginSubmit.setOnClickListener { submit() }
        views.loginInput.clearErrorOnChange(viewLifecycleOwner)
        views.loginPasswordInput.clearErrorOnChange(viewLifecycleOwner)
        views.loginLoadingInfo.setOnClickListener { loadingInfo() }

        combine(views.loginInput.editText().textChanges(), views.loginPasswordInput.editText().textChanges()) { account, password ->
            views.loginSubmit.isEnabled = account.isNotEmpty() && password.isNotEmpty()
        }.flowWithLifecycle(lifecycle).launchIn(viewLifecycleOwner.lifecycleScope)
        views.officialWebsite.paint.flags = Paint.UNDERLINE_TEXT_FLAG
        views.officialWebsite.paint.isAntiAlias = true
        views.officialWebsite.debouncedClicks { openUrlInExternalBrowser(requireActivity(), getString(R.string.official_website_url)) }
    }

    private fun submit() {
        cleanupUi()
        loginFieldsValidation.validate(views.loginInput.content(), views.loginPasswordInput.content())
                .onUsernameOrIdError { views.loginInput.error = it }
                .onPasswordError { views.loginPasswordInput.error = it }
                .onValid { usernameOrId, password ->
                    val initialDeviceName = getString(R.string.login_default_session_public_name)
                    viewModel.handle(OnboardingAction.AuthenticateAction.Login(usernameOrId, password, initialDeviceName))
                }
    }

    private fun cleanupUi() {
        views.loginSubmit.hideKeyboard()
        views.loginInput.error = null
        views.loginPasswordInput.error = null
    }

    override fun resetViewModel() {
        viewModel.handle(OnboardingAction.ResetAuthenticationAttempt)
    }

    override fun onError(throwable: Throwable) {
        // Trick to display the error without text.
        views.loginInput.error = " "
        loginErrorParser.parse(throwable, views.loginPasswordInput.content())
                .onUnknown { super.onError(it) }
                .onUsernameOrIdError { views.loginInput.error = it }
                .onPasswordError { views.loginPasswordInput.error = it }
    }

    override fun updateWithState(state: OnboardingViewState) {
        setupUi(state)
        setupAutoFill()

        views.selectedServerName.text = state.selectedHomeserver.userFacingUrl.toReducedUrl()

        if (state.isLoading) {
            // Ensure password is hidden
            views.loginPasswordInput.editText().hidePassword()
        }
    }

    private fun setupUi(state: OnboardingViewState) {
        when (state.selectedHomeserver.preferredLoginMode) {
            is LoginMode.SsoAndPassword -> {
                showUsernamePassword()
                renderSsoProviders(state.deviceId, state.selectedHomeserver.preferredLoginMode)
            }
            is LoginMode.Sso -> {
                hideUsernamePassword()
                renderSsoProviders(state.deviceId, state.selectedHomeserver.preferredLoginMode)
            }
            else -> {
                showUsernamePassword()
                hideSsoProviders()
            }
        }
    }

    private fun renderSsoProviders(deviceId: String?, loginMode: LoginMode) {
        views.ssoGroup.isVisible = true
        views.ssoButtonsHeader.isVisible = isUsernameAndPasswordVisible()
        views.ssoButtons.render(loginMode, SocialLoginButtonsView.Mode.MODE_CONTINUE) { id ->
            viewModel.fetchSsoUrl(
                    redirectUrl = SSORedirectRouterActivity.VECTOR_REDIRECT_URL,
                    deviceId = deviceId,
                    provider = id,
                    action = SSOAction.LOGIN
            )?.let { openInCustomTab(it) }
        }
    }

    private fun hideSsoProviders() {
        views.ssoGroup.isVisible = false
        views.ssoButtons.ssoIdentityProviders = null
    }

    private fun hideUsernamePassword() {
        views.loginEntryGroup.isVisible = false
    }

    private fun showUsernamePassword() {
        views.loginEntryGroup.isVisible = true
    }

    private fun isUsernameAndPasswordVisible() = views.loginEntryGroup.isVisible

    private fun setupAutoFill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            views.loginInput.setAutofillHints(HintConstants.AUTOFILL_HINT_NEW_USERNAME)
            views.loginPasswordInput.setAutofillHints(HintConstants.AUTOFILL_HINT_NEW_PASSWORD)
        }
    }
}
