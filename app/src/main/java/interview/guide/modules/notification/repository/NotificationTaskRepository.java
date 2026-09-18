package interview.guide.modules.notification.repository;

import interview.guide.modules.notification.model.NotificationTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 通知任务仓储。(bizType, bizId) 唯一约束是幂等投递的数据库兜底。
 */
@Repository
public interface NotificationTaskRepository extends JpaRepository<NotificationTaskEntity, Long> {

    Optional<NotificationTaskEntity> findByBizTypeAndBizId(String bizType, Long bizId);

    List<NotificationTaskEntity> findTop100ByOrderByIdDesc();

    List<NotificationTaskEntity> findByStatusOrderByIdDesc(String status);
}
