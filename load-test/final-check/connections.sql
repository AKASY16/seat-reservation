-- 애플리케이션 계정의 DB 연결 수를 접속한 컨테이너 주소별로 센다. 서버마다 커넥션 풀 크기(20)와 같아야 한다.
SELECT SUBSTRING_INDEX(HOST, ':', 1) AS client_ip, COUNT(*) AS connections
FROM performance_schema.processlist
WHERE USER = 'app'
GROUP BY client_ip
ORDER BY client_ip;
