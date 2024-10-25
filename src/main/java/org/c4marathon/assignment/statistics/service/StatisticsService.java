package org.c4marathon.assignment.statistics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.c4marathon.assignment.statistics.domain.Statistics;
import org.c4marathon.assignment.statistics.domain.repository.StatisticsRepository;
import org.c4marathon.assignment.statistics.dto.StatisticsResponse;
import org.c4marathon.assignment.transaction.domain.Transaction;
import org.c4marathon.assignment.transaction.domain.repository.TransactionRepository;
import org.c4marathon.assignment.util.QueryExecuteTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {


    private final TransactionRepository transactionRepository;
    private final StatisticsRepository statisticsRepository;

    /*
    * 새벽 4시에 그 전날 통계를 내는 작업
    * 새벽 4시에 그 전날 date를 가져와서 실행
    * */
    @Scheduled(cron = "0 0 4 * * ?")
    public void scheduleStatistics() {
        calculateScheduleStatistics();
    }

    public void calculateScheduleStatistics() {
        LocalDate date = LocalDate.now().minusDays(1L);

        Long latestCumulativeRemittance = statisticsRepository.getLatestCumulativeRemittance();

        QueryExecuteTemplate.<Transaction>selectAndExecuteWithCursorAndPageLimit(-1, 1000,
                lastTransaction -> transactionRepository.findTransactionByDate(
                        date,
                        lastTransaction == null ? null : lastTransaction.getTransactionDate(),
                        lastTransaction == null ? 0 : lastTransaction.getId(),
                        1000),
                transactionList -> calculateTotalRemittance(transactionList, date, latestCumulativeRemittance)
        );
    }

    public void calculateTotalRemittance(List<Transaction> transactionList, LocalDate date, Long latestCumulativeRemittance) {

        long totalRemittance = transactionList.stream()
                .mapToLong(Transaction::getAmount)
                .sum();

        latestCumulativeRemittance += totalRemittance;

        saveOrUpdateStatistics(date, totalRemittance, latestCumulativeRemittance);
    }

    public void calculateStatistics(int pageSize, LocalDate endDate) {

        AtomicLong latestCumulativeRemittance = new AtomicLong(statisticsRepository.getLatestCumulativeRemittance());

        QueryExecuteTemplate.<Transaction>selectAndExecuteWithCursorAndPageLimit(pageSize, 1000,
                lastTransaction -> transactionRepository.findTransactionByLastDate(
                        endDate,
                        lastTransaction == null ? null : lastTransaction.getTransactionDate(),
                        lastTransaction == null ? 0 : lastTransaction.getId(),
                        1000),
                transactionList -> latestCumulativeRemittance.set(processTransactionBatch(transactionList, latestCumulativeRemittance.get()))
        );
    }

    /**
     * 시작날짜와 종료날짜를 입력받아 통계 데이터를 조회하는 로직
     * @param startDate
     * @param endDate
     * @return
     */
    public List<StatisticsResponse> getStatisticsByStartDateAndEndDate(LocalDate startDate, LocalDate endDate) {
        List<Statistics> statisticsByDate = statisticsRepository.findByStatisticsByStartDateAndEndDate(startDate, endDate);

        return statisticsByDate.stream()
                .map(StatisticsResponse::new)
                .toList();
    }

    /*
     * 넘어온 거래 데이터를 날짜별로 그룹화해서 계산해서 통계 테이블에 저장
     * 만약 그 날짜에 해당하는 통계 데이터가 있으면 거기다가 누적해서 저장
     * */
    private Long processTransactionBatch(List<Transaction> transactionList, Long latestCumulativeRemittance) {
        ZoneId zoneId = ZoneId.of("UTC");

        //transactionList 에서 데이터가 1/1, 1/2 같이들어올 경우
        // 여기서 맵으로 만들때 1/2일이 먼저 들어갈 수 있다...?
        // 그래서 누적할 때 꼬인다 이거 한 번 체크해보기 -> TreeMap 로 날짜로 정렬
        Map<LocalDate, List<Transaction>> map = transactionList.stream()
                .collect(Collectors.groupingBy(
                        transaction -> LocalDate.ofInstant(transaction.getTransactionDate(), zoneId),
                        TreeMap::new,
                        Collectors.toList()
                ));


        for (Map.Entry<LocalDate, List<Transaction>> entry : map.entrySet()) {
            LocalDate transactionDate = entry.getKey();
            List<Transaction> transactionsByDate = entry.getValue();

            long totalRemittance = transactionsByDate.stream()
                    .mapToLong(Transaction::getAmount)
                    .sum();

            latestCumulativeRemittance += totalRemittance;

            saveOrUpdateStatistics(transactionDate, totalRemittance, latestCumulativeRemittance);
        }
        return latestCumulativeRemittance;
    }

    private void saveOrUpdateStatistics(LocalDate transactionDate, Long totalRemittance, Long latestCumulativeRemittance) {

        Statistics statistics = statisticsRepository.findByStatisticsDate(transactionDate);

        if (statistics == null) {
            statistics = Statistics.of(
                    transactionDate,
                    totalRemittance,
                    latestCumulativeRemittance,
                    LocalDateTime.now()
            );
        } else {
            statistics.update(
                    transactionDate,
                    totalRemittance,
                    statistics.getCumulativeRemittance() + totalRemittance,
                    LocalDateTime.now()
            );
        }
        statisticsRepository.save(statistics);

    }

}
