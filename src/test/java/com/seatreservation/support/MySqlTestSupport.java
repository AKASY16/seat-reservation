package com.seatreservation.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/**
 DB를 쓰는 테스트가 상속한다. 동시성 규칙은 InnoDB의 잠금 동작에 기대고 있어서 H2가 아닌 실제 MySQL에서 검증한다.

 컨테이너를 static으로 두고 직접 start해서 테스트 JVM 전체에서 하나만 쓴다.
 **/
public abstract class MySqlTestSupport {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("seat_reservation");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    protected static MySQLContainer mysql() {
        return MYSQL;
    }
}
