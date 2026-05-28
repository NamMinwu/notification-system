-- 기본 알림 템플릿 시드 (4 타입 × 2 채널, ko). Mustache 문법. 운영 중 업서트 API로 수정 가능.
INSERT INTO notification_template
    (notification_type, channel, language, subject, body, version, is_active, created_at, updated_at)
VALUES
    ('ENROLLMENT_COMPLETE', 'EMAIL', 'ko',
     '[수강신청] {{className}} 신청이 완료되었습니다',
     '{{userName}}님, {{className}} 수강 신청이 정상적으로 완료되었습니다.', 1, true, now(), now()),
    ('ENROLLMENT_COMPLETE', 'IN_APP', 'ko',
     '수강 신청 완료',
     '{{className}} 신청이 완료되었어요.', 1, true, now(), now()),

    ('PAYMENT_CONFIRMED', 'EMAIL', 'ko',
     '[결제완료] 결제가 확정되었습니다',
     '{{userName}}님, {{amount}}원 결제가 확정되었습니다.', 1, true, now(), now()),
    ('PAYMENT_CONFIRMED', 'IN_APP', 'ko',
     '결제 완료',
     '{{amount}}원 결제가 확정되었어요.', 1, true, now(), now()),

    ('CLASS_REMINDER_D1', 'EMAIL', 'ko',
     '[리마인드] 내일 {{className}} 강의가 시작됩니다',
     '{{userName}}님, 내일 {{startTime}}에 {{className}} 강의가 시작됩니다.', 1, true, now(), now()),
    ('CLASS_REMINDER_D1', 'IN_APP', 'ko',
     '강의 시작 D-1',
     '내일 {{startTime}} {{className}} 강의가 시작돼요.', 1, true, now(), now()),

    ('CANCELLATION_PROCESSED', 'EMAIL', 'ko',
     '[취소완료] 취소가 처리되었습니다',
     '{{userName}}님, {{className}} 취소가 처리되었습니다.', 1, true, now(), now()),
    ('CANCELLATION_PROCESSED', 'IN_APP', 'ko',
     '취소 처리 완료',
     '{{className}} 취소가 처리되었어요.', 1, true, now(), now());
