package com.seatreservation.common;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;

// MySQL DATETIME 컬럼에 저장할 수 있는 시각인지 검사한다.
// 범위를 벗어난 값은 저장할 때 DB 오류(500)가 나고 재전송해도 성공할 수 없으므로, 트랜잭션에 들어가기 전에 400으로 거절한다.
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StorableDateTime.Validator.class)
public @interface StorableDateTime {

    String message() default "1000-01-01T00:00:00Z부터 9999-12-31T23:59:59.999999Z 사이여야 합니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<StorableDateTime, Instant> {

        // MySQL 문서에 적힌 DATETIME의 지원 범위다. 시각은 UTC로 저장한다.
        private static final Instant MIN = Instant.parse("1000-01-01T00:00:00Z");
        private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

        @Override
        public boolean isValid(Instant value, ConstraintValidatorContext context) {
            // 값이 없는 경우는 @NotNull이 따로 검사한다.
            return value == null || (!value.isBefore(MIN) && !value.isAfter(MAX));
        }
    }
}
