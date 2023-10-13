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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import im.vector.app.core.utils.openUrlInExternalBrowser
import im.vector.app.core.utils.toast
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
import java.io.BufferedReader
import java.io.InputStreamReader
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
        openFile()
    }

    private var openFileResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result ->
        //处理返回的结果
        val code = result.resultCode //返回码 如：Activity.RESULT_OK、Activity.RESULT_CANCELED76500 3600 6717 80100 86817
        val data = result.data

//        链接：https://juejin.cn/post/7237014602751279161

        if (code == Activity.RESULT_OK) {
            // The result data contains a URI for the document or directory that
            // the user selected.
            data?.data?.also { uri ->
                // Perform operations on the document using its URI.

                var content = readTextFromUri(uri)
                if (!content.isNullOrEmpty()){
                    content.trim()
                    if (content.length>5){
                        setDecryptPasswordAlert(requireActivity(), content)
                    }else{
                        Toast.makeText(requireActivity(),R.string.import_service_account_file_is_empty,Toast.LENGTH_LONG).show()
                    }
                }else{
                    Toast.makeText(requireActivity(),R.string.import_service_account_file_is_empty,Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun readTextFromUri(uri: Uri): String {
        val contentResolver = requireActivity().contentResolver
        val stringBuilder = StringBuilder()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    stringBuilder.append(line)
                    line = reader.readLine()
                }
            }
        }
        return stringBuilder.toString()
    }
    private fun openFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"

            // Optionally, specify a URI for the file that should appear in the
            // system file picker when it loads.
            //选择器初始化uri
            val pickerInitialUri: Uri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Documents"
            )
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri)
        }
        openFileResultLauncher.launch(intent)
    }

    private fun setDecryptPasswordAlert(context: Context, content: String){
        var builder = AlertDialog.Builder(context)
        builder.setTitle(R.string.popup_service_account_title)
        builder.setMessage(R.string.popup_service_account_file_loading_msg)
        val editText = EditText(context)
        setupEditTextInputLengthLimit(editText, 16)
        editText.hint = context.getString(R.string.popup_service_account_edittext_hint)

        val linearLayout = LinearLayout(context)
        linearLayout.orientation = LinearLayout.VERTICAL
        linearLayout.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams.setMargins(48, 0, 48, 0)
        editText.layoutParams = layoutParams
        linearLayout.addView(editText)

        builder.setView(linearLayout)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.dialog_title_confirmation,null)
        builder.setNegativeButton(R.string.action_cancel,null)
        var dialogs: AlertDialog = builder.create()
        if (!dialogs.isShowing){
            dialogs.show()
        }
        dialogs.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener {
            var input = editText.text.toString()
            val targetLength = 16
            val paddingChar = '0'

            if (input.length < 16) {
                input = input.padEnd(targetLength, paddingChar)
            }
            var redInfo = AESCryptUtils.decrypt(content,input)
            if (redInfo.isEmpty()){
                Toast.makeText(context,R.string.popup_service_account_password_error,Toast.LENGTH_LONG).show()
            }else{
                try {
                    val pattern = Regex("@(\\w+):([^\\s:]+)")
                    val matchResult = pattern.find(redInfo)

                    if (matchResult != null) {
                        val (substringAccount, substringHost) = matchResult.destructured
                        val serverUrl = "https://${substringHost}/"
                        views.loginEditText.setText(substringAccount)
                        viewModel.handle(OnboardingAction.HomeServerChange.EditHomeServer(serverUrl))
                    }
                } catch (e: Exception) {
                    requireActivity().toast("请选择正确的文件")
                }
                dialogs.cancel()
            }
        })
        dialogs.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dialogs.cancel()
        }
    }
    private fun setupEditTextInputLengthLimit(editText: EditText, maxLength: Int) {
        val filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLength))
        editText.filters = filters
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
