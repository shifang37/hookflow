package dev.hookflow.persistence;

import dev.hookflow.domain.outbox.OutboxMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OutboxMapper {

    @Insert("""
            INSERT INTO outbox (aggregate_type, aggregate_id, attempt_no, payload, status)
            VALUES (#{aggregateType}, #{aggregateId}, #{attemptNo}, #{payload}, 'NEW')
            """)
    int insert(@Param("aggregateType") String aggregateType,
               @Param("aggregateId") long aggregateId,
               @Param("attemptNo") int attemptNo,
               @Param("payload") String payload);

    /**
     * Locks the next batch for exactly one relay instance.
     *
     * <p>{@code SKIP LOCKED} is what makes horizontal relay scaling safe: a second
     * relay steps over rows that are already locked instead of blocking or
     * re-publishing them.
     */
    @Select("""
            SELECT * FROM outbox
             WHERE status = 'NEW'
             ORDER BY id
             LIMIT #{limit}
             FOR UPDATE SKIP LOCKED
            """)
    List<OutboxMessage> lockBatch(@Param("limit") int limit);

    @Update("<script>"
            + "UPDATE outbox SET status = 'PUBLISHING', publishing_since = NOW(3),"
            + " publish_attempts = publish_attempts + 1"
            + " WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int markPublishing(@Param("ids") List<Long> ids);

    @Update("UPDATE outbox SET status = 'PUBLISHED', published_at = NOW(3) WHERE id = #{id}")
    int markPublished(@Param("id") long id);

    /** Put a failed publish back in the queue with an incremented attempt counter. */
    @Update("UPDATE outbox SET status = 'NEW', publishing_since = NULL WHERE id = #{id} AND status = 'PUBLISHING'")
    int releaseBack(@Param("id") long id);

    /**
     * Recovery for the crash window "broker confirmed, row not yet marked".
     * <p>Rows stuck in PUBLISHING are re-published, which is precisely why the
     * end-to-end guarantee is at-least-once and not exactly-once.
     */
    @Update("""
            UPDATE outbox
               SET status = 'NEW', publishing_since = NULL
             WHERE status = 'PUBLISHING'
               AND publishing_since < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)
             LIMIT #{limit}
            """)
    int recoverStalePublishing(@Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * Re-arms a row that the broker accepted but that produced no effect.
     *
     * <p>{@code PUBLISHED} only means "the broker took it", not "a worker completed
     * the attempt". If the message was lost after the confirm, or the consumer
     * nacked it, the intent is still true but the row is already PUBLISHED, so a
     * plain INSERT of the same attempt would be rejected by the uniqueness key and
     * the delivery would sit in RETRY_WAIT forever. Re-arming after a grace window
     * closes that hole without weakening the uniqueness guarantee.
     */
    @Update("""
            UPDATE outbox
               SET status = 'NEW', publishing_since = NULL, published_at = NULL
             WHERE aggregate_type = #{aggregateType}
               AND aggregate_id = #{aggregateId}
               AND attempt_no = #{attemptNo}
               AND status = 'PUBLISHED'
               AND published_at < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)
            """)
    int rearmStalePublished(@Param("aggregateType") String aggregateType,
                            @Param("aggregateId") long aggregateId,
                            @Param("attemptNo") int attemptNo,
                            @Param("staleSeconds") int staleSeconds);

    @Select("SELECT COUNT(*) FROM outbox WHERE status = 'NEW'")
    long countPending();

    /**
     * Work that the broker has not accepted yet.
     *
     * <p>Distinct from {@link #countPending()} on purpose: a row cycles between NEW
     * and PUBLISHING every relay tick while the broker is down, so NEW alone reads as
     * zero exactly when the backlog is worst. This is the number an operator should
     * alert on.
     */
    @Select("SELECT COUNT(*) FROM outbox WHERE status IN ('NEW', 'PUBLISHING')")
    long countUnpublished();

    /**
     * Prunes rows that have been published longer than the retention window.
     *
     * <p>Without this the table grows without bound: one row per delivery attempt,
     * forever. Kept as a bounded batch so a large backlog cannot turn into one
     * multi-minute DELETE that blocks the relay.
     */
    @Delete("""
            DELETE FROM outbox
             WHERE status = 'PUBLISHED'
               AND published_at < DATE_SUB(NOW(3), INTERVAL #{retentionDays} DAY)
             LIMIT #{batchSize}
            """)
    int deletePublishedBefore(@Param("retentionDays") int retentionDays, @Param("batchSize") int batchSize);
}
