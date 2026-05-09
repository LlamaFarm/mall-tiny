package com.macro.mall.tiny.modules.ums.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.macro.mall.tiny.modules.ums.mapper.UmsResourceMapper;
import com.macro.mall.tiny.modules.ums.model.UmsResource;
import com.macro.mall.tiny.modules.ums.service.UmsAdminCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link UmsResourceServiceImpl}.
 *
 * <p>Resource management is the foundation of dynamic permission checks: every
 * mutation must invalidate the per-admin resource cache so an admin's effective
 * permissions match the latest DB state.
 */
@ExtendWith(MockitoExtension.class)
class UmsResourceServiceImplTest {

    @Mock
    private UmsResourceMapper resourceMapper;

    @Mock
    private UmsAdminCacheService adminCacheService;

    @Spy
    @InjectMocks
    private UmsResourceServiceImpl service;

    @Test
    void should_setCreateTimeAndPersist_when_creatingResource() {
        UmsResource resource = new UmsResource();
        resource.setName("brand:read");
        doReturn(true).when(service).save(resource);

        boolean success = service.create(resource);

        assertThat(success).isTrue();
        assertThat(resource.getCreateTime()).isNotNull().isBeforeOrEqualTo(new Date());
        verify(service).save(resource);
        verifyNoInteractions(adminCacheService);
    }

    @Test
    void should_assignIdAndInvalidateCaches_when_updatingResource() {
        UmsResource resource = new UmsResource();
        resource.setName("brand:write");
        doReturn(true).when(service).updateById(resource);

        boolean success = service.update(7L, resource);

        assertThat(success).isTrue();
        assertThat(resource.getId())
                .as("update() must stamp the path id onto the entity")
                .isEqualTo(7L);
        verify(service).updateById(resource);
        verify(adminCacheService).delResourceListByResource(7L);
    }

    @Test
    void should_returnFalseAndStillInvalidateCache_when_updateMapperReportsNoRowChange() {
        UmsResource resource = new UmsResource();
        doReturn(false).when(service).updateById(resource);

        boolean success = service.update(7L, resource);

        assertThat(success).isFalse();
        verify(adminCacheService).delResourceListByResource(7L);
    }

    @Test
    void should_invalidateCache_when_deletingResource() {
        doReturn(true).when(service).removeById(42L);

        boolean success = service.delete(42L);

        assertThat(success).isTrue();
        verify(service).removeById(42L);
        verify(adminCacheService).delResourceListByResource(42L);
    }

    @Test
    void should_returnFalseAndStillInvalidateCache_when_deleteMapperReportsNoRowChange() {
        doReturn(false).when(service).removeById(42L);

        boolean success = service.delete(42L);

        assertThat(success).isFalse();
        verify(adminCacheService).delResourceListByResource(42L);
    }

    @Test
    void should_pageWithoutFilters_when_allFiltersAreNullOrBlank() {
        Page<UmsResource> expected = new Page<>(2, 10);
        ArgumentCaptor<Page<UmsResource>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        doReturn(expected).when(service).page(pageCaptor.capture(), any(Wrapper.class));

        Page<UmsResource> result = service.list(null, null, "", 10, 2);

        assertThat(result).isSameAs(expected);
        Page<UmsResource> passedPage = pageCaptor.getValue();
        assertThat(passedPage.getCurrent()).isEqualTo(2);
        assertThat(passedPage.getSize()).isEqualTo(10);
    }

    @Test
    void should_pageWithEveryFilter_when_categoryAndKeywordsProvided() {
        Page<UmsResource> expected = new Page<>(1, 5);
        doReturn(expected).when(service).page(any(Page.class), any(Wrapper.class));

        Page<UmsResource> result = service.list(3L, "brand", "/api/brand", 5, 1);

        assertThat(result).isSameAs(expected);
        verify(service).page(any(Page.class), any(Wrapper.class));
    }
}
