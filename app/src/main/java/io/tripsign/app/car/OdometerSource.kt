// ver 1.0.3 | 2026-11-04 11:40
package io.tripsign.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Odometer
import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions

/**
 * Odometer (distance driven) from the Pleos Vehicle SDK.
 * Called the same way the official Pleos sample explorer calls it (kotlin-reflect + listener proxy),
 * which was verified on the emulator (value read and live updates 972 -> 2,217).
 */
class OdometerSource(
    context: Context,
    private val onKm: (Double) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val log: (String) -> Unit
) {
    private val vehicle = Vehicle(context.applicationContext)
    private var initialized = false
    private var odometer: Any? = null
    private var listener: Any? = null
    private var unregisterFn: KFunction<*>? = null

    fun start() {
        try {
            vehicle.initialize()
            initialized = true
        } catch (e: Exception) {
            log("Vehicle 초기화 실패: $e")
            onConnected(false)
            return
        }
        val odo: Any = try {
            vehicle.getOdometer()
        } catch (e: Exception) {
            log("getOdometer 실패: $e")
            onConnected(false)
            return
        }
        odometer = odo
        val functions = Odometer::class.declaredMemberFunctions
        log("Odometer 함수: " + functions.joinToString { f -> f.name + "(" + f.parameters.filter { it.kind == KParameter.Kind.VALUE }.joinToString { p -> "${p.name}:${p.type}" } + ")" })
        readNow()
        val register = functions.firstOrNull { it.name == "registerDistanceDriven" }
        if (register == null) {
            log("registerDistanceDriven 없음")
        } else {
            val l = createListener(register)
            listener = l
            unregisterFn = functions.firstOrNull { it.name == "unregisterDistanceDriven" }
            val ok = invoke(register, odo, l)
            onConnected(ok)
        }
    }

    fun readNow() {
        val odo = odometer ?: return
        val get = Odometer::class.declaredMemberFunctions.firstOrNull { it.name == "getCurrentDistanceDriven" }
        if (get == null) {
            log("getCurrentDistanceDriven 없음")
            return
        }
        invoke(get, odo, null)
    }

    fun stop() {
        val odo = odometer
        val l = listener
        val un = unregisterFn
        if (odo != null && l != null && un != null) invoke(un, odo, l)
        listener = null
        if (initialized) {
            try {
                vehicle.release()
            } catch (e: Exception) {
                log("Vehicle 해제 실패: $e")
            }
            initialized = false
        }
    }

    private fun invoke(function: KFunction<*>, instance: Any, listenerArg: Any?): Boolean {
        val args = mutableMapOf<KParameter, Any?>()
        for (p in function.parameters) {
            if (p.kind == KParameter.Kind.INSTANCE) {
                args[p] = instance
                continue
            }
            val name = p.name.orEmpty()
            val cls = p.type.classifier as? KClass<*>
            if (cls == null) {
                log("${function.name}: 알 수 없는 인자 형식 $name:${p.type}")
                return false
            }
            val value: Any? = when {
                cls == Function0::class -> {
                    val f: () -> Unit = { log("${function.name}.$name()") }
                    f
                }
                cls == Function1::class -> {
                    val isFailure = name.contains("fail", ignoreCase = true)
                    val f: (Any?) -> Unit = { x ->
                        if (isFailure) log("${function.name}.$name: $x") else handle(x, function.name)
                    }
                    f
                }
                cls == Function2::class -> {
                    val f: (Any?, Any?) -> Unit = { a, b -> handle(b ?: a, function.name) }
                    f
                }
                listenerArg != null && cls.java.isInstance(listenerArg) -> listenerArg
                cls.java.isEnum -> pickEnum(cls.java)
                cls == Boolean::class -> false
                cls == Int::class -> 0
                cls == Float::class -> 0f
                else -> null
            }
            if (value != null) {
                args[p] = value
            } else if (p.isOptional) {
                continue
            } else if (p.type.isMarkedNullable) {
                args[p] = null
            } else {
                log("${function.name}: 인자 생성 불가 $name:${p.type}")
                return false
            }
        }
        return try {
            val result = function.callBy(args)
            log("${function.name} 호출 결과=$result")
            result != false
        } catch (e: Exception) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            log("${function.name} 실패: $cause")
            false
        }
    }

    private fun createListener(register: KFunction<*>): Any? {
        val listenerParam = register.parameters.firstOrNull {
            (it.type.classifier as? KClass<*>)?.qualifiedName?.startsWith("ai.pleos.playground.vehicle.listener.") == true
        } ?: return null
        val listenerClass = (listenerParam.type.classifier as KClass<*>).java
        return Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, arguments ->
            when (method.name) {
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "TripSignOdometerListener"
                else -> {
                    if (method.name.contains("fail", ignoreCase = true)) {
                        log("${listenerClass.simpleName}.${method.name}: ${arguments?.firstOrNull()}")
                    } else {
                        handle(arguments?.firstOrNull(), method.name)
                    }
                    Unit
                }
            }
        }
    }

    private fun pickEnum(cls: Class<*>): Any? {
        val constants = cls.enumConstants ?: return null
        return constants.firstOrNull { (it as Enum<*>).name.contains("KILO", ignoreCase = true) || (it as Enum<*>).name == "KM" }
            ?: constants.firstOrNull()
    }

    private var lastLoggedKm: Double? = null

    private fun handle(value: Any?, source: String) {
        val km = toKm(value)
        if (km != lastLoggedKm) {
            log("$source 값=$value -> $km")
            lastLoggedKm = km
        }
        if (km != null && km >= 0.0 && !km.isNaN()) onKm(km)
    }

    private fun toKm(value: Any?): Double? {
        if (value == null) return null
        if (value is Number) return value.toDouble()
        val getters = value.javaClass.methods.filter {
            it.parameterCount == 0 && it.name.startsWith("get") && Number::class.java.isAssignableFrom(it.returnType.kotlin.javaObjectType)
        }
        val preferred = getters.firstOrNull { it.name.contains("Distance", ignoreCase = true) || it.name == "getValue" }
            ?: getters.firstOrNull()
        return try {
            (preferred?.invoke(value) as? Number)?.toDouble()
        } catch (e: Exception) {
            null
        }
    }
}
