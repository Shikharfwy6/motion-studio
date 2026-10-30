package com.motionstudio.app

/**
 * Bridge to the C++ core (libmotioncore.so): GPU effects and the particle simulation.
 * If the library cannot be loaded the app keeps working without effects.
 */
object NativeFx {
    @JvmStatic var ok = false
        private set

    init {
        try {
            System.loadLibrary("motioncore")
            ok = true
        } catch (t: Throwable) {
            ok = false
        }
    }

    @JvmStatic external fun nInit(): Boolean
    @JvmStatic external fun nRelease()
    @JvmStatic external fun nBegin(w: Int, h: Int)
    @JvmStatic external fun nRun(params: FloatArray): Int
    @JvmStatic external fun nParticles(params: FloatArray, out: FloatArray): Int
}
