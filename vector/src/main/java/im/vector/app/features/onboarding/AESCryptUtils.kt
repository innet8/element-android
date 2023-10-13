package im.vector.app.features.onboarding

import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AESCryptUtils {
    //AES加密
    /**
     * AES密钥最低长度为16
     * password AES密钥
     */
    private const val defaultIv = "xy8z56abxy8z56ab"
    fun encrypt(input:String, password:String): String{
        //初始化cipher对象
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        // 生成密钥
        val keySpec: SecretKeySpec? = SecretKeySpec(password.toByteArray(),"AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE,keySpec, IvParameterSpec(defaultIv.toByteArray(Charsets.UTF_8)))
        //加密解密
        val encrypt = cipher.doFinal(input.toByteArray())
        return bytesToHex(encrypt)
    }
    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789ABCDEF"
        val result = StringBuilder(bytes.size * 2)

        for (byte in bytes) {
            val index = byte.toInt() and 0xFF
            result.append(hexChars[index ushr 4])
            result.append(hexChars[index and 0x0F])
        }

        return result.toString()
    }

    //AES解密
    fun decrypt(input: String, password: String): String {
        //初始化cipher对象
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        // 生成密钥
        val keySpec: SecretKeySpec? = SecretKeySpec(password.toByteArray(), "AES/CBC/PKCS5Padding")

        val encrypt = try {
            //加密解密
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(defaultIv.toByteArray(Charsets.UTF_8)))
            if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
                cipher.doFinal(hexToBytes(input))
            }else{
//                cipher.doFinal(Base64.getDecoder().decode(input.toByteArray()))
                cipher.doFinal(hexToBytes(input))
            }
        } catch (e: Exception) {
            return ""
        }
        //AES解密不需要用Base64解码

        return String(encrypt)
    }
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)

        for (i in 0 until len step 2) {
            val byte = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            data[i / 2] = byte
        }

        return data
    }
}
