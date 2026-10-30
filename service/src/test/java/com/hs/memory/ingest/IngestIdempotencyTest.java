package com.hs.memory.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hs.memory.api.IngestRequest;
import com.hs.memory.api.IngestResponse;
import com.hs.memory.config.RuntimeConfig;
import com.hs.memory.obs.MetricsRecorder;
import com.hs.memory.repo.MemoryRepository;
import com.hs.memory.repo.NewItem;
import com.hs.memory.vector.QdrantClient;
import com.hs.memory.vector.VectorRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * 幂等键被复用时的行为。
 *
 * <p>线上真事：同一个会话今天的第 1、2 轮被三四天前的旧第 1、2 轮顶掉——接口 200、
 * 库里一条没有。成因是幂等键只按「会话 + 轮次」拼，调用方重启后轮次从 1 重新数，
 * 于是新对话撞进旧键，被当成「重投」丢弃。重投该去重，但内容不同的新对话不该。
 */
class IngestIdempotencyTest {

    private MemoryRepository repo;
    private IngestService service;

    @BeforeEach
    void setUp() {
        repo = mock(MemoryRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new IngestService(mock(RuntimeConfig.class), repo, mock(QdrantClient.class),
                mock(VectorRegistry.class), txManager, mock(MetricsRecorder.class));
    }

    private IngestRequest request(String idemKey, String narrative) {
        return new IngestRequest(idemKey, "u1", null, "qq:g:1",
                List.of(new IngestRequest.Item("episode", "user", "T2", narrative,
                        Map.of(), List.of("怎么问"), null, null, null,
                        null, null, null, null, null, "turn", 1)),
                null);
    }

    @Test
    @DisplayName("键相同、内容也相同 ⇒ 当成重投，不重复写")
    void sameContentIsRetry() {
        // 第一次写入：拿到服务算出的内容指纹
        when(repo.findByKey(anyString(), anyString())).thenReturn(Optional.empty());
        ArgumentCaptor<NewItem> first = ArgumentCaptor.forClass(NewItem.class);
        when(repo.insert(first.capture())).thenReturn(Optional.of(UUID.randomUUID()));
        service.ingest("t1", null, request("qq:g:1:1", "端口是 8080"));
        String hash = first.getValue().contentHash();

        // 再投一遍同样的内容：库里已有该键、指纹一致
        clearInvocations(repo);
        when(repo.findByKey(anyString(), anyString()))
                .thenReturn(Optional.of(new MemoryRepository.Keyed(UUID.randomUUID(), hash)));
        IngestResponse again = service.ingest("t1", null, request("qq:g:1:1", "端口是 8080"));

        assertThat(again.accepted()).isZero();
        verify(repo, never()).insert(any());
    }

    @Test
    @DisplayName("键相同、内容不同 ⇒ 不是重投，换个带指纹的键照写")
    void differentContentIsNewTurn() {
        when(repo.findByKey(anyString(), anyString()))
                .thenReturn(Optional.of(new MemoryRepository.Keyed(UUID.randomUUID(), "旧内容的指纹")));
        ArgumentCaptor<NewItem> captor = ArgumentCaptor.forClass(NewItem.class);
        when(repo.insert(captor.capture())).thenReturn(Optional.of(UUID.randomUUID()));

        IngestResponse response = service.ingest("t1", null, request("qq:g:1:1", "今天群里在聊太刀"));

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(captor.getValue().idemKey()).startsWith("qq:g:1:1~");
        assertThat(captor.getValue().idemKey()).isNotEqualTo("qq:g:1:1");
    }
}
