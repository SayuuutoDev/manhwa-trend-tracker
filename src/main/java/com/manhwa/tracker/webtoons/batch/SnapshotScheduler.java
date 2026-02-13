package com.manhwa.tracker.webtoons.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.scrape", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SnapshotScheduler {
    private final JobLauncher jobLauncher;
    private final Job scrapeJob;
    private final Job asuraScrapeJob;
    private final Job tapasScrapeJob;

    public SnapshotScheduler(JobLauncher jobLauncher, Job scrapeJob, Job asuraScrapeJob, Job tapasScrapeJob) {
        this.jobLauncher = jobLauncher;
        this.scrapeJob = scrapeJob;
        this.asuraScrapeJob = asuraScrapeJob;
        this.tapasScrapeJob = tapasScrapeJob;
    }

    @Scheduled(cron = "${app.snapshot.cron}", zone = "${app.snapshot.zone:UTC}")
    public void runWeeklySnapshot() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("JobID", String.valueOf(System.currentTimeMillis()))
                .toJobParameters();
        jobLauncher.run(scrapeJob, params);
    }

    @Scheduled(cron = "${app.asura.cron:${app.snapshot.cron}}", zone = "${app.snapshot.zone:UTC}")
    public void runAsuraSnapshot() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("JobID", String.valueOf(System.currentTimeMillis()))
                .addString("Source", "ASURA")
                .toJobParameters();
        jobLauncher.run(asuraScrapeJob, params);
    }

    @Scheduled(cron = "${app.tapas.cron:${app.snapshot.cron}}", zone = "${app.snapshot.zone:UTC}")
    public void runTapasSnapshot() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("JobID", String.valueOf(System.currentTimeMillis()))
                .addString("Source", "TAPAS")
                .toJobParameters();
        jobLauncher.run(tapasScrapeJob, params);
    }
}
