package com.team.silveragent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开了定时：每周一次的健康小结靠它（见 WeeklyHealthReportJob）。 */
@SpringBootApplication
@EnableScheduling
public class SilverAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(SilverAgentApplication.class, args);
    }
}
