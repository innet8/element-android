package im.vector.app.features.onboarding

import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object AESCryptUtils {
    //AES加密
    /**
     * AES密钥最低长度为16
     * password AES密钥
     */
    fun encrypt(input:String, password:String): String{
        //初始化cipher对象
        val cipher = Cipher.getInstance("AES")
        // 生成密钥
        val keySpec: SecretKeySpec? = SecretKeySpec(password.toByteArray(),"AES")
        cipher.init(Cipher.ENCRYPT_MODE,keySpec)
        //加密解密
        val encrypt = cipher.doFinal(input.toByteArray())

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            val result = Base64.getEncoder().encode(encrypt)
            String(result)
        }else{
            val result = android.util.Base64.decode(encrypt,android.util.Base64.DEFAULT)
            String(result)
        }
    }

    //AES解密
    fun decrypt(input: String, password: String): String {
        //初始化cipher对象
        val cipher = Cipher.getInstance("AES")
        // 生成密钥
        val keySpec: SecretKeySpec? = SecretKeySpec(password.toByteArray(), "AES")

        val encrypt = try {
            //加密解密
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                cipher.doFinal(Base64.getDecoder().decode(input.toByteArray()))
            }else{
                cipher.doFinal(android.util.Base64.decode(input.toByteArray(),android.util.Base64.DEFAULT))
            }

        } catch (e: Exception) {
            return ""
        }
        //AES解密不需要用Base64解码

        return String(encrypt)
    }
}
