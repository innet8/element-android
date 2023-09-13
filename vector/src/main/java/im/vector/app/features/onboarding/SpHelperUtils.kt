package im.vector.app.features.onboarding

import android.content.Context

object SpHelperUtils {
    private const val FILE_NAME = "SP"
    /**
     * 保存数据的方法，我们需要拿到保存数据的具体类型，然后根据类型调用不同的保存方法
     *
     * @param context
     * @param key
     * @param `object`
     */
    fun put(context: Context, key: String?, data: Any) {
        val editor = context.getSharedPreferences(SpHelperUtils.FILE_NAME,Context.MODE_PRIVATE).edit()
        when (data) {
            is String -> editor.putString(key, data)
            is Int -> editor.putInt(key, data)
            is Boolean -> editor.putBoolean(key, data)
            is Float -> editor.putFloat(key, data)
            is Long -> editor.putLong(key, data)
            else ->editor.putString(key, data.toString())
        }
        editor.apply()
    }

    /**
     * 得到保存数据的方法，我们根据默认值得到保存的数据的具体类型，然后调用相对于的方法获取值
     *
     * @param context
     * @param key
     * @param Defaultdata
     * @return
     */
    operator fun get(context: Context, key: String?, Defaultdata: Any?): Any? {
        val sp = context.getSharedPreferences(SpHelperUtils.FILE_NAME,Context.MODE_PRIVATE)
        return when (Defaultdata) {
            is String -> sp.getString(key, Defaultdata as String?)
            is Int -> sp.getInt(key, (Defaultdata as Int?)!!)
            is Boolean -> sp.getBoolean(key, (Defaultdata as Boolean?)!!)
            is Float -> sp.getFloat(key, (Defaultdata as Float?)!!)
            is Long -> sp.getLong(key, (Defaultdata as Long?)!!)
            else -> null
        }
    }

//    原文链接：https://blog.csdn.net/weixin_39531948/article/details/115682138
}
