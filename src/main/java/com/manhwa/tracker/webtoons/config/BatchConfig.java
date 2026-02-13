package com.manhwa.tracker.webtoons.config;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import com.manhwa.tracker.webtoons.batch.WebtoonsReader;
import com.manhwa.tracker.webtoons.model.ManhwaDTO;
import com.manhwa.tracker.webtoons.batch.AsuraSeriesReader;
import com.manhwa.tracker.webtoons.batch.AsuraSeriesProcessor;
import com.manhwa.tracker.webtoons.model.AsuraSeriesDTO;
import com.manhwa.tracker.webtoons.batch.TapasSeriesReader;
import com.manhwa.tracker.webtoons.batch.TapasSeriesProcessor;
import com.manhwa.tracker.webtoons.model.TapasSeriesDTO;
import com.manhwa.tracker.webtoons.model.MetricSnapshot;
import com.manhwa.tracker.webtoons.repository.MetricSnapshotRepository;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import com.manhwa.tracker.webtoons.batch.WebtoonsProcessor;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class BatchConfig {

    @Bean
    public Job scrapeJob(JobRepository jobRepository, Step scrapingStep) {
        return new JobBuilder("webtoonsScrapeJob", jobRepository)
                .start(scrapingStep)
                .build();
    }

    @Bean
    public Job asuraScrapeJob(JobRepository jobRepository, Step asuraScrapingStep) {
        return new JobBuilder("asuraScrapeJob", jobRepository)
                .start(asuraScrapingStep)
                .build();
    }

    @Bean
    public Job tapasScrapeJob(JobRepository jobRepository, Step tapasScrapingStep) {
        return new JobBuilder("tapasScrapeJob", jobRepository)
                .start(tapasScrapingStep)
                .build();
    }

    @Bean
    public Step scrapingStep(JobRepository jobRepository,
                             PlatformTransactionManager transactionManager,
                             WebtoonsReader reader,
                             WebtoonsProcessor processor, // Inject the processor here
                             MetricSnapshotRepository repository) {
        return new StepBuilder("scrapingStep", jobRepository)
                .<ManhwaDTO, MetricSnapshot>chunk(10, transactionManager)
                .reader(reader)
                .processor(processor) // Tell Spring Batch to use the processor
                .writer(chunk -> {
                    repository.saveAll(chunk.getItems());
                })
                .build();
    }

    @Bean
    public Step asuraScrapingStep(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  AsuraSeriesReader reader,
                                  AsuraSeriesProcessor processor,
                                  MetricSnapshotRepository repository) {
        return new StepBuilder("asuraScrapingStep", jobRepository)
                .<AsuraSeriesDTO, MetricSnapshot>chunk(10, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(chunk -> repository.saveAll(chunk.getItems()))
                .build();
    }

    @Bean
    public Step tapasScrapingStep(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  TapasSeriesReader reader,
                                  TapasSeriesProcessor processor,
                                  MetricSnapshotRepository repository) {
        return new StepBuilder("tapasScrapingStep", jobRepository)
                .<TapasSeriesDTO, List<MetricSnapshot>>chunk(10, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(chunk -> {
                    List<MetricSnapshot> all = new ArrayList<>();
                    for (List<MetricSnapshot> item : chunk.getItems()) {
                        if (item != null) {
                            all.addAll(item);
                        }
                    }
                    if (!all.isEmpty()) {
                        repository.saveAll(all);
                    }
                })
                .build();
    }
}
