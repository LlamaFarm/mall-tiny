package com.macro.mall.tiny.modules.ums.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.macro.mall.tiny.common.service.RedisService;
import com.macro.mall.tiny.modules.ums.mapper.UmsAdminMapper;
import com.macro.mall.tiny.modules.ums.model.UmsAdmin;
import com.macro.mall.tiny.modules.ums.model.UmsAdminRoleRelation;
import com.macro.mall.tiny.modules.ums.model.UmsResource;
import com.macro.mall.tiny.modules.ums.service.UmsAdminRoleRelationService;
import com.macro.mall.tiny.modules.ums.service.UmsAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UmsAdminCacheServiceImpl}.
 *
 * <p>Cache keys are built from {@code @Value}-injected prefixes; we set them via
 * {@link ReflectionTestUtils} so the assembled keys match the production format
 * exactly. A mismatched key would silently miss the Redis cache in production.
 */
@ExtendWith(MockitoExtension.class)
class UmsAdminCacheServiceImplTest {

    private static final String DB = "mall-tiny";
    private static final String ADMIN_KEY = "ums:admin";
    private static final String RESOURCE_KEY = "ums:admin:resourceList";
    private static final long EXPIRE_SECONDS = 3600L;

    @Mock
    private UmsAdminService adminService;
    @Mock
    private RedisService redisService;
    @Mock
    private UmsAdminMapper adminMapper;
    @Mock
    private UmsAdminRoleRelationService adminRoleRelationService;

    @InjectMocks
    private UmsAdminCacheServiceImpl cacheService;

    @BeforeEach
    void wireValueProperties() {
        ReflectionTestUtils.setField(cacheService, "REDIS_DATABASE", DB);
        ReflectionTestUtils.setField(cacheService, "REDIS_EXPIRE", EXPIRE_SECONDS);
        ReflectionTestUtils.setField(cacheService, "REDIS_KEY_ADMIN", ADMIN_KEY);
        ReflectionTestUtils.setField(cacheService, "REDIS_KEY_RESOURCE_LIST", RESOURCE_KEY);
    }

    private static UmsAdmin admin(Long id, String username) {
        UmsAdmin admin = new UmsAdmin();
        admin.setId(id);
        admin.setUsername(username);
        return admin;
    }

    private static UmsAdminRoleRelation relation(Long adminId) {
        UmsAdminRoleRelation rel = new UmsAdminRoleRelation();
        rel.setAdminId(adminId);
        return rel;
    }

    @Test
    void should_evictByUsernameKey_when_adminExists() {
        UmsAdmin admin = admin(1L, "alice");
        when(adminService.getById(1L)).thenReturn(admin);

        cacheService.delAdmin(1L);

        verify(redisService).del(DB + ":" + ADMIN_KEY + ":alice");
    }

    @Test
    void should_doNothing_when_admin_isAbsent() {
        when(adminService.getById(99L)).thenReturn(null);

        cacheService.delAdmin(99L);

        verifyNoInteractions(redisService);
    }

    @Test
    void should_evictResourceListByAdminId_when_delResourceListCalled() {
        cacheService.delResourceList(7L);

        verify(redisService).del(DB + ":" + RESOURCE_KEY + ":7");
    }

    @Test
    void should_evictAllAffectedAdmins_when_invalidatingByRole() {
        when(adminRoleRelationService.list(any(Wrapper.class)))
                .thenReturn(Arrays.asList(relation(10L), relation(11L)));
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);

        cacheService.delResourceListByRole(3L);

        verify(redisService).del(keys.capture());
        assertThat(keys.getValue()).containsExactly(
                DB + ":" + RESOURCE_KEY + ":10",
                DB + ":" + RESOURCE_KEY + ":11");
    }

    @Test
    void should_skipRedisDelete_when_noAdminsAssignedToRole() {
        when(adminRoleRelationService.list(any(Wrapper.class)))
                .thenReturn(Collections.emptyList());

        cacheService.delResourceListByRole(3L);

        verify(redisService, never()).del(any(List.class));
        verify(redisService, never()).del(any(String.class));
    }

    @Test
    void should_evictAcrossMultipleRoles_when_invalidatingByRoleIds() {
        when(adminRoleRelationService.list(any(Wrapper.class)))
                .thenReturn(Arrays.asList(relation(10L), relation(20L), relation(30L)));
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);

        cacheService.delResourceListByRoleIds(Arrays.asList(1L, 2L));

        verify(redisService).del(keys.capture());
        assertThat(keys.getValue()).containsExactly(
                DB + ":" + RESOURCE_KEY + ":10",
                DB + ":" + RESOURCE_KEY + ":20",
                DB + ":" + RESOURCE_KEY + ":30");
    }

    @Test
    void should_skipRedisDelete_when_roleIdsHaveNoAssignedAdmins() {
        when(adminRoleRelationService.list(any(Wrapper.class)))
                .thenReturn(Collections.emptyList());

        cacheService.delResourceListByRoleIds(Arrays.asList(1L, 2L));

        verify(redisService, never()).del(any(List.class));
    }

    @Test
    void should_evictAffectedAdminCaches_when_resourceChanges() {
        when(adminMapper.getAdminIdList(50L)).thenReturn(Arrays.asList(101L, 102L));
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);

        cacheService.delResourceListByResource(50L);

        verify(redisService).del(keys.capture());
        assertThat(keys.getValue()).containsExactly(
                DB + ":" + RESOURCE_KEY + ":101",
                DB + ":" + RESOURCE_KEY + ":102");
    }

    @Test
    void should_skipRedisDelete_when_resourceUsedByNoAdmins() {
        when(adminMapper.getAdminIdList(50L)).thenReturn(Collections.emptyList());

        cacheService.delResourceListByResource(50L);

        verify(redisService, never()).del(any(List.class));
    }

    @Test
    void should_lookupAdminByUsernameKey_when_getAdminCalled() {
        UmsAdmin cached = admin(1L, "alice");
        when(redisService.get(DB + ":" + ADMIN_KEY + ":alice")).thenReturn(cached);

        UmsAdmin result = cacheService.getAdmin("alice");

        assertThat(result).isSameAs(cached);
    }

    @Test
    void should_writeAdminWithExpire_when_setAdminCalled() {
        UmsAdmin admin = admin(1L, "alice");

        cacheService.setAdmin(admin);

        verify(redisService).set(DB + ":" + ADMIN_KEY + ":alice", admin, EXPIRE_SECONDS);
    }

    @Test
    void should_lookupResourceListByAdminId_when_getResourceListCalled() {
        List<UmsResource> cached = Collections.singletonList(new UmsResource());
        when(redisService.get(DB + ":" + RESOURCE_KEY + ":7")).thenReturn(cached);

        List<UmsResource> result = cacheService.getResourceList(7L);

        assertThat(result).isSameAs(cached);
    }

    @Test
    void should_writeResourceListWithExpire_when_setResourceListCalled() {
        List<UmsResource> resources = Collections.singletonList(new UmsResource());

        cacheService.setResourceList(7L, resources);

        verify(redisService).set(DB + ":" + RESOURCE_KEY + ":7", resources, EXPIRE_SECONDS);
    }
}
