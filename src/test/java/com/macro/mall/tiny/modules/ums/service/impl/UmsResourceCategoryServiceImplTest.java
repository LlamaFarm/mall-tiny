package com.macro.mall.tiny.modules.ums.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.macro.mall.tiny.modules.ums.mapper.UmsResourceCategoryMapper;
import com.macro.mall.tiny.modules.ums.model.UmsResourceCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link UmsResourceCategoryServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class UmsResourceCategoryServiceImplTest {

    @Mock
    private UmsResourceCategoryMapper resourceCategoryMapper;

    @Spy
    @InjectMocks
    private UmsResourceCategoryServiceImpl service;

    @Test
    void should_returnCategoriesOrderedBySortDesc_when_listAllCalled() {
        UmsResourceCategory first = new UmsResourceCategory();
        first.setSort(10);
        UmsResourceCategory second = new UmsResourceCategory();
        second.setSort(5);
        List<UmsResourceCategory> expected = Arrays.asList(first, second);
        doReturn(expected).when(service).list(any(Wrapper.class));

        List<UmsResourceCategory> result = service.listAll();

        assertThat(result).isSameAs(expected);
        verify(service).list(any(Wrapper.class));
    }

    @Test
    void should_returnEmptyList_when_noCategoriesExist() {
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        List<UmsResourceCategory> result = service.listAll();

        assertThat(result).isEmpty();
    }

    @Test
    void should_setCreateTimeAndPersist_when_creatingCategory() {
        UmsResourceCategory category = new UmsResourceCategory();
        category.setName("permission");
        doReturn(true).when(service).save(category);

        boolean success = service.create(category);

        assertThat(success).isTrue();
        assertThat(category.getCreateTime())
                .as("create() must populate createTime so callers don't have to")
                .isNotNull()
                .isBeforeOrEqualTo(new Date());
        verify(service).save(category);
    }

    @Test
    void should_propagateMapperFailure_when_saveReturnsFalse() {
        UmsResourceCategory category = new UmsResourceCategory();
        doReturn(false).when(service).save(category);

        boolean success = service.create(category);

        assertThat(success).isFalse();
    }
}
