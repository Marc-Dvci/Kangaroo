package com.kangaroo.core;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Measured numbers, from a tap-to-count, a thermometer, a scale or a BLE sensor.
 *
 * <p>Everything is optional because the field reality is that most of it is missing most of the
 * time. {@code -1} is the absent sentinel on the wire (it is what the trained models were given
 * for "missing"), but the accessors hand back {@link OptionalInt}/{@link OptionalDouble} so no
 * caller can accidentally treat a missing respiratory rate as a rate of minus one.
 *
 * @param respiratoryRate breaths per minute, counted over a full 60 seconds
 * @param temperatureC    axillary temperature in Celsius
 * @param spo2            peripheral oxygen saturation in percent, from a BLE pulse oximeter
 * @param heartRate       beats per minute
 */
public record Vitals(int respiratoryRate, double temperatureC, int spo2, int heartRate) {

    public static final int ABSENT_INT = -1;
    public static final double ABSENT_DOUBLE = -1.0;

    public static Vitals none() {
        return new Vitals(ABSENT_INT, ABSENT_DOUBLE, ABSENT_INT, ABSENT_INT);
    }

    public OptionalInt respiratoryRateOpt() {
        return respiratoryRate > 0 ? OptionalInt.of(respiratoryRate) : OptionalInt.empty();
    }

    public OptionalDouble temperatureOpt() {
        return temperatureC > 0 ? OptionalDouble.of(temperatureC) : OptionalDouble.empty();
    }

    public OptionalInt spo2Opt() {
        return spo2 > 0 ? OptionalInt.of(spo2) : OptionalInt.empty();
    }

    /** WHO IMNCI fast-breathing threshold for infants under 2 months. */
    public boolean fastBreathing() { return respiratoryRate >= 60; }

    public boolean fever() { return temperatureC >= 38.0; }

    public boolean hypothermia() { return temperatureC > 0 && temperatureC < 35.5; }

    /** Below this, a pulse oximeter reading is itself a referral trigger. */
    public boolean hypoxaemia() { return spo2 > 0 && spo2 < 90; }

    public Vitals withRespiratoryRate(int rr) {
        return new Vitals(rr, temperatureC, spo2, heartRate);
    }

    public Vitals withTemperature(double t) {
        return new Vitals(respiratoryRate, t, spo2, heartRate);
    }

    public Vitals withSpo2(int s) {
        return new Vitals(respiratoryRate, temperatureC, s, heartRate);
    }
}
