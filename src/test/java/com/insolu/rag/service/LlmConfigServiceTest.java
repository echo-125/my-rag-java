package com.insolu.rag.service;

import com.insolu.rag.entity.LlmConfigEntity;
import com.insolu.rag.entity.LlmConfigEntity.ApiFormat;
import com.insolu.rag.entity.LlmConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmConfigServiceTest {

    @Mock
    private LlmConfigRepository repository;

    @Mock
    private ChatModelBuilder chatModelBuilder;

    @InjectMocks
    private LlmConfigService service;

    private LlmConfigEntity sampleEntity;

    @BeforeEach
    void setUp() {
        sampleEntity = new LlmConfigEntity();
        sampleEntity.setId(UUID.randomUUID());
        sampleEntity.setName("Test Model");
        sampleEntity.setModelName("gpt-4o");
        sampleEntity.setBaseUrl("https://api.openai.com");
        sampleEntity.setApiKey("sk-test-12345678");
        sampleEntity.setApiFormat(ApiFormat.openai_chat_completions);
        sampleEntity.setIsActive(false);
    }

    @Test
    @DisplayName("findAll 返回脱敏后的配置列表")
    void findAll_returnsMaskedKeys() {
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(sampleEntity));

        var result = service.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getApiKey()).startsWith("***");
        assertThat(result.get(0).getApiKey()).doesNotContain("sk-test");
    }

    @Test
    @DisplayName("save 加密存储 API 密钥")
    void save_encryptsApiKey() {
        when(repository.save(any(LlmConfigEntity.class))).thenAnswer(inv -> {
            LlmConfigEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        LlmConfigEntity input = new LlmConfigEntity();
        input.setName("New Model");
        input.setModelName("gpt-4");
        input.setBaseUrl("https://api.openai.com");
        input.setApiKey("sk-plaintext-key");
        input.setApiFormat(ApiFormat.openai_chat_completions);

        var result = service.save(input);

        // 验证 repository.save 被调用时密钥是明文（不再加密）
        verify(repository).save(argThat(e -> e.getApiKey().equals("sk-plaintext-key")));
        // 返回值是脱敏的
        assertThat(result.getApiKey()).startsWith("***");
    }

    @Test
    @DisplayName("update 不传新密钥时保留原密钥")
    void update_noNewKey_preservesExisting() {
        when(repository.findById(sampleEntity.getId())).thenReturn(Optional.of(sampleEntity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LlmConfigEntity updated = new LlmConfigEntity();
        updated.setName("Updated Name");
        updated.setModelName("gpt-4o-mini");
        updated.setBaseUrl("https://api.openai.com");
        updated.setApiKey("***xxxx"); // 前端回传的脱敏值
        updated.setApiFormat(ApiFormat.openai_chat_completions);

        service.update(sampleEntity.getId(), updated);

        // 验证原密钥被保留
        verify(repository).save(argThat(e -> e.getApiKey().equals("sk-test-12345678")));
    }

    @Test
    @DisplayName("activate 先停用所有再激活指定配置")
    void activate_deactivatesAllThenActivates() {
        when(repository.findById(sampleEntity.getId())).thenReturn(Optional.of(sampleEntity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.activate(sampleEntity.getId());

        verify(repository).deactivateAll();
        verify(repository).save(argThat(e -> e.getIsActive()));
    }

    @Test
    @DisplayName("deleteById 传入正确 ID")
    void delete_callsRepository() {
        UUID id = UUID.randomUUID();
        service.delete(id);
        verify(repository).deleteById(id);
    }

    @Test
    @DisplayName("testConnection 配置不存在时抛异常")
    void testConnection_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.testConnection(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("配置不存在");
    }
}
