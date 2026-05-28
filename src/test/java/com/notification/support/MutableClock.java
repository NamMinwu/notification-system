package com.notification.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트용 가변 Clock — 시간을 명시적으로 전진시켜 재시도/예약 동작을 결정적으로 검증한다. */
public class MutableClock extends Clock {

	private Instant instant;
	private final ZoneId zone;

	public MutableClock(Instant instant) {
		this(instant, ZoneOffset.UTC);
	}

	private MutableClock(Instant instant, ZoneId zone) {
		this.instant = instant;
		this.zone = zone;
	}

	public void advance(Duration duration) {
		this.instant = this.instant.plus(duration);
	}

	public void setInstant(Instant instant) {
		this.instant = instant;
	}

	@Override
	public Instant instant() {
		return instant;
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return new MutableClock(instant, zone);
	}
}
