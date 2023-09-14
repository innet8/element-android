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
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.autofill.HintConstants
import androidx.core.text.isDigitsOnly
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.activityViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.clearErrorOnChange
import im.vector.app.core.extensions.content
import im.vector.app.core.extensions.editText
import im.vector.app.core.extensions.hasSurroundingSpaces
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.hidePassword
import im.vector.app.core.extensions.isMatrixId
import im.vector.app.core.extensions.onTextChange
import im.vector.app.core.extensions.realignPercentagesToParent
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.extensions.setOnFocusLostListener
import im.vector.app.core.extensions.setOnImeDoneListener
import im.vector.app.core.extensions.toReducedUrl
import im.vector.app.core.utils.ensureProtocol
import im.vector.app.core.utils.ensureTrailingSlash
import im.vector.app.core.utils.openUrlInChromeCustomTab
import im.vector.app.core.utils.openUrlInExternalBrowser
import im.vector.app.databinding.FragmentFtueCombinedRegisterBinding
import im.vector.app.features.login.LoginMode
import im.vector.app.features.login.SSORedirectRouterActivity
import im.vector.app.features.login.SocialLoginButtonsView
import im.vector.app.features.login.qr.QrCodeLoginAction
import im.vector.app.features.login.render
import im.vector.app.features.onboarding.OnboardingAction
import im.vector.app.features.onboarding.OnboardingAction.AuthenticateAction
import im.vector.app.features.onboarding.OnboardingViewEvents
import im.vector.app.features.onboarding.OnboardingViewState
import im.vector.app.features.onboarding.SingleUrl
import im.vector.app.features.qrcode.QrCodeScannerActivity
import im.vector.app.features.settings.VectorLocale
import im.vector.app.features.usercode.UserCodeShareViewEvents
import im.vector.app.features.usercode.UserCodeSharedViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import org.checkerframework.nonapi.io.github.classgraph.json.JSONUtils
import org.checkerframework.org.apache.commons.lang3.SystemUtils
import org.json.JSONObject
import org.matrix.android.sdk.api.auth.SSOAction
import org.matrix.android.sdk.api.failure.isHomeserverUnavailable
import org.matrix.android.sdk.api.failure.isInvalidPassword
import org.matrix.android.sdk.api.failure.isInvalidUsername
import org.matrix.android.sdk.api.failure.isLoginEmailUnknown
import org.matrix.android.sdk.api.failure.isRegistrationDisabled
import org.matrix.android.sdk.api.failure.isUsernameInUse
import org.matrix.android.sdk.api.failure.isWeakPassword
import reactivecircus.flowbinding.android.widget.textChanges
import timber.log.Timber
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val MINIMUM_PASSWORD_LENGTH = 8

@AndroidEntryPoint
class FtueAuthCombinedRegisterFragment :
        AbstractSSOFtueAuthFragment<FragmentFtueCombinedRegisterBinding>() {
    @Inject lateinit var languageVectorLocale: VectorLocale
    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFtueCombinedRegisterBinding {
        return FragmentFtueCombinedRegisterBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSubmitButton()
        views.createAccountRoot.realignPercentagesToParent()
        views.editServerButton.debouncedClicks { viewModel.handle(OnboardingAction.PostViewEvent(OnboardingViewEvents.EditServerSelection)) }
        views.createAccountPasswordInput.setOnImeDoneListener {
            if (canSubmit(views.createAccountInput.content(), views.createAccountPasswordInput.content())) {
                submit()
            }
        }

        views.createAccountInput.onTextChange(viewLifecycleOwner) {
            viewModel.handle(OnboardingAction.ResetSelectedRegistrationUserName)
            views.createAccountEntryFooter.text = ""
        }

        views.createAccountInput.setOnFocusLostListener(viewLifecycleOwner) {
            viewModel.handle(OnboardingAction.UserNameEnteredAction.Registration(views.createAccountInput.content()))
        }

        if (!SingleUrl.serviceUrl.isNullOrEmpty()){
            updateAddress(SingleUrl.serviceUrl)
        }
        views.loginLanguageSelect.text = languageVectorLocale.localeToLocalisedString(languageVectorLocale.applicationLocale)
        views.loginLanguage.debouncedClicks{
            viewModel.handle(OnboardingAction.PostViewEvent(OnboardingViewEvents.OnLanguageClicked))
        }
    }

    /**
     * 升级服务器
     */
    private fun updateAddress(address: String) {
        viewModel.handle(OnboardingAction.HomeServerChange.EditHomeServer(address))
        views.createAccountInvite.setText(SingleUrl.inviteCode)
    }

    private fun canSubmit(account: CharSequence, password: CharSequence): Boolean {
        val accountIsValid = account.isNotEmpty()
        val passwordIsValid = password.length >= MINIMUM_PASSWORD_LENGTH
        return accountIsValid && passwordIsValid
    }

    private fun setupSubmitButton() {
        views.createAccountSubmit.setOnClickListener { submit() }
        views.createAccountInput.clearErrorOnChange(viewLifecycleOwner)
        views.createAccountPasswordInput.clearErrorOnChange(viewLifecycleOwner)

        combine(views.createAccountInput.editText().textChanges(), views.createAccountPasswordInput.editText().textChanges()) { account, password ->
            views.createAccountSubmit.isEnabled = canSubmit(account, password)
        }.launchIn(viewLifecycleOwner.lifecycleScope)
        views.showUserCodeScanButton.debouncedClicks {
            QrCodeScannerActivity.startForResult(requireActivity(), scanActivityResultLauncher)
        }
        views.officialWebsite.paint.flags = Paint.UNDERLINE_TEXT_FLAG
        views.officialWebsite.paint.isAntiAlias = true
        views.officialWebsite.debouncedClicks { openUrlInExternalBrowser(requireActivity(), getString(R.string.official_website_url)) }
    }
    private val scanActivityResultLauncher = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val scannedQrCode = QrCodeScannerActivity.getResultText(activityResult.data)
            val wasQrCode = QrCodeScannerActivity.getResultIsQrCode(activityResult.data)

            Timber.d("Scanned QR code: $scannedQrCode, was QR code: $wasQrCode")
            if (wasQrCode && !scannedQrCode.isNullOrBlank()) {
                onQrCodeScanned(scannedQrCode)
            } else {
                onQrCodeScannerFailed()
            }
        }
    }
    private fun onQrCodeScanned(scannedQrCode: String) {
        if (scannedQrCode.contains("rgs_token=")){
            val urlLinkAfter: String = scannedQrCode.toString().substringAfter("rgs_token=")
            Timber.d("Scanned QR code: $urlLinkAfter")
            if (!urlLinkAfter.isNullOrBlank()){
                views.createAccountInvite.setText(urlLinkAfter)
            }else{
                Toast.makeText(activity,"无效的二维码",Toast.LENGTH_SHORT).show()
            }
        }else{
            Toast.makeText(activity,R.string.invalid_qr_code_uri,Toast.LENGTH_SHORT).show()
        }

    }

    private fun onQrCodeScannerFailed() {
        // The user scanned something unexpected, so we try scanning again.
        // This seems to happen particularly with the large QRs needed for rendezvous
        // especially when the QR is partially off the screen
        Timber.d("QrCodeLoginInstructionsFragment.onQrCodeScannerFailed - showing scanner again")
        QrCodeScannerActivity.startForResult(requireActivity(), scanActivityResultLauncher)
    }
    private fun submit() {
        withState(viewModel) { state ->
            cleanupUi()

            val login = views.createAccountInput.content()
            val password = views.createAccountPasswordInput.content()
            val inviteCode = views.createAccountInviteInput.content()

            // This can be called by the IME action, so deal with empty cases
            var error = 0
            if (login.isEmpty()) {
                views.createAccountInput.error = getString(R.string.error_empty_field_choose_user_name)
                error++
            }
            if (state.isNumericOnlyUserIdForbidden() && login.isDigitsOnly()) {
                views.createAccountInput.error = getString(R.string.error_forbidden_digits_only_username)
                error++
            }
            if (password.isEmpty()) {
                views.createAccountPasswordInput.error = getString(R.string.error_empty_field_choose_password)
                error++
            }

            if (error == 0) {
                val initialDeviceName = getString(R.string.login_default_session_public_name)
                val registerAction = when {
                    login.isMatrixId() -> AuthenticateAction.RegisterWithMatrixId(login, password, initialDeviceName)
                    else -> AuthenticateAction.Register(login, password, initialDeviceName)
                }
                if (inviteCode.isEmpty()) {
                    viewModel.handle(registerAction)
                }else{
//                    viewModel.handle(AuthenticateAction.RegisterInviteCode(login, password, inviteCode, initialDeviceName))
                    val server = views.selectedServerName.text
                    val url = "https://$server/_synapse/admin/v1/registration_tokens/$inviteCode"

                    val trustAllCertificates = arrayOf<TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    })

                    val sslContext = SSLContext.getInstance("TLS").apply {
                        init(null, trustAllCertificates, SecureRandom())
                    }

                    val client = OkHttpClient.Builder()
                            .sslSocketFactory(sslContext.socketFactory, trustAllCertificates[0] as X509TrustManager)
                            .hostnameVerifier { _, _ -> true }
                            .build()
                    //创建request请求对象
                    val request = Request.Builder()
                            .url(url)
                            //.method()方法与.get()方法选取1种即可
                            .method("GET", null)
                            .build()


                    //创建call并调用enqueue()方法实现网络请求
                    client.newCall(request)
                            .enqueue(object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
                                    println("error")
                                    activity?.runOnUiThread{
                                        Toast.makeText(activity, "network eero", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                override fun onResponse(call: Call, response: Response) {
                                    println("aaaaaaaaa-->$response")
                                    val responseCode = response.code
                                    println("responseCode-->$responseCode")
                                    if (responseCode == 200 ){
                                        val responseBody = response.body
                                        println("responseBody-->$responseBody")
                                        val jsonString: String? = responseBody?.string()
                                        println("jsonString-->$jsonString")
                                        val jsonObject = JSONObject(jsonString.toString())
                                        val token: String = jsonObject.getString("token")
                                        println("jsonObject-->${token}")
                                        viewModel.handle(registerAction)
                                        SingleUrl.inviteCode = inviteCode;
                                    }else{
                                        println("错误-->")
                                        activity?.runOnUiThread {
                                            Toast.makeText(activity, "invitation code error", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            })
                }

            }
        }
    }

    private fun cleanupUi() {
        views.createAccountSubmit.hideKeyboard()
        views.createAccountInput.error = null
        views.createAccountPasswordInput.error = null
        views.createAccountInviteInput.error = null
    }

    override fun resetViewModel() {
        viewModel.handle(OnboardingAction.ResetAuthenticationAttempt)
    }

    override fun onError(throwable: Throwable) {
        // Trick to display the error without text.
        views.createAccountInput.error = " "
        when {
            throwable.isUsernameInUse() || throwable.isInvalidUsername() -> {
                views.createAccountInput.error = errorFormatter.toHumanReadable(throwable)
            }
            throwable.isLoginEmailUnknown() -> {
                views.createAccountInput.error = getString(R.string.login_login_with_email_error)
            }
            throwable.isInvalidPassword() && views.createAccountPasswordInput.hasSurroundingSpaces() -> {
                views.createAccountPasswordInput.error = getString(R.string.auth_invalid_login_param_space_in_password)
            }
            throwable.isWeakPassword() || throwable.isInvalidPassword() -> {
                views.createAccountPasswordInput.error = errorFormatter.toHumanReadable(throwable)
            }
            throwable.isHomeserverUnavailable() -> {
                views.createAccountInput.error = getString(R.string.login_error_homeserver_not_found)
            }
            throwable.isRegistrationDisabled() -> {
                MaterialAlertDialogBuilder(requireActivity())
                        .setTitle(R.string.dialog_title_error)
                        .setMessage(getString(R.string.login_registration_disabled))
                        .setPositiveButton(R.string.ok, null)
                        .show()
            }
            else -> {
                super.onError(throwable)
            }
        }
    }

    override fun updateWithState(state: OnboardingViewState) {
        setupUi(state)
        setupAutoFill()
    }

    private fun setupUi(state: OnboardingViewState) {
        views.selectedServerName.text = state.selectedHomeserver.userFacingUrl.toReducedUrl()

        if (state.isLoading) {
            // Ensure password is hidden
            views.createAccountPasswordInput.editText().hidePassword()
        }

        views.createAccountEntryFooter.text = when {
            state.registrationState.isUserNameAvailable -> getString(
                    R.string.ftue_auth_create_account_username_entry_footer,
                    state.registrationState.selectedMatrixId
            )

            else -> ""
        }

        when (state.selectedHomeserver.preferredLoginMode) {
            is LoginMode.SsoAndPassword -> renderSsoProviders(state.deviceId, state.selectedHomeserver.preferredLoginMode)
            else -> hideSsoProviders()
        }
    }

    private fun renderSsoProviders(deviceId: String?, loginMode: LoginMode) {
        views.ssoGroup.isVisible = true
        views.ssoButtons.render(loginMode, SocialLoginButtonsView.Mode.MODE_CONTINUE) { provider ->
            viewModel.fetchSsoUrl(
                    redirectUrl = SSORedirectRouterActivity.VECTOR_REDIRECT_URL,
                    deviceId = deviceId,
                    provider = provider,
                    action = SSOAction.REGISTER
            )?.let { openInCustomTab(it) }
        }
    }

    private fun hideSsoProviders() {
        views.ssoGroup.isVisible = false
        views.ssoButtons.ssoIdentityProviders = null
    }

    private fun setupAutoFill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            views.createAccountInput.setAutofillHints(HintConstants.AUTOFILL_HINT_NEW_USERNAME)
            views.createAccountPasswordInput.setAutofillHints(HintConstants.AUTOFILL_HINT_NEW_PASSWORD)
        }
    }

    private fun OnboardingViewState.isNumericOnlyUserIdForbidden() = selectedHomeserver.userFacingUrl == getString(R.string.matrix_org_server_url)
}
