package dev.distill.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "distill.queue.polling-enabled=false")
class WorkerApplicationTests {

    @Test
    void shouldLoadApplicationContext() {
    }
}
