package com.manhwa.tracker.webtoons;


import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.annotation.Profile;
@SpringBootApplication
@EnableScheduling
public class ScraperWebtoonsApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScraperWebtoonsApplication.class, args);
	}
	@Bean
	@ConditionalOnProperty(prefix = "app.scrape", name = "enabled", havingValue = "true", matchIfMissing = true)
	@Profile("scrape")
	CommandLineRunner runJob(JobLauncher jobLauncher, Job scrapeJob) {
		return args -> {
			JobParameters params = new JobParametersBuilder()
					.addString("JobID", String.valueOf(System.currentTimeMillis()))
					.toJobParameters();
			jobLauncher.run(scrapeJob, params);
		};
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.asura", name = "run-once", havingValue = "true")
	@Profile("scrape")
	CommandLineRunner runAsuraJob(JobLauncher jobLauncher, Job asuraScrapeJob) {
		return args -> {
			JobParameters params = new JobParametersBuilder()
					.addString("JobID", String.valueOf(System.currentTimeMillis()))
					.addString("Source", "ASURA")
					.toJobParameters();
			jobLauncher.run(asuraScrapeJob, params);
		};
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.tapas", name = "run-once", havingValue = "true")
	@Profile("scrape")
	CommandLineRunner runTapasJob(JobLauncher jobLauncher, Job tapasScrapeJob) {
		return args -> {
			JobParameters params = new JobParametersBuilder()
					.addString("JobID", String.valueOf(System.currentTimeMillis()))
					.addString("Source", "TAPAS")
					.toJobParameters();
			jobLauncher.run(tapasScrapeJob, params);
		};
	}
}
