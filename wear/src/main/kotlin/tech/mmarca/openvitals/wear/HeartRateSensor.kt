package tech.mmarca.openvitals.wear

import android.hardware.Sensor
import android.hardware.SensorManager

/**
 * The heart rate sensor every part of the watch app uses. The recorder has always used the wake-up
 * variant, which on the Galaxy Watch is "Samsung HR Batch Sensor". A spot measurement, the live
 * stream and the HRV window used the other one ("HR None Wakeup") and the watch showed no green
 * LEDs for them, so they now use this one as well. That the wake-up variant is the one that lights
 * the LEDs is a guess from this difference.
 */
internal fun SensorManager.heartRateSensor(): Sensor? =
    getDefaultSensor(Sensor.TYPE_HEART_RATE, true) ?: getDefaultSensor(Sensor.TYPE_HEART_RATE)
