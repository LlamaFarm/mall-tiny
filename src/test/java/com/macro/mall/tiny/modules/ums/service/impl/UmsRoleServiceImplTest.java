package com.macro.mall.tiny.modules.ums.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.macro.mall.tiny.modules.ums.mapper.UmsMenuMapper;
import com.macro.mall.tiny.modules.ums.mapper.UmsResourceMapper;
import com.macro.mall.tiny.modules.ums.mapper.UmsRoleMapper;
import com.macro.mall.tiny.modules.ums.model.UmsMenu;
import com.macro.mall.tiny.modules.ums.model.UmsResource;
import com.macro.mall.tiny.modules.ums.model.UmsRole;
import com.macro.mall.tiny.modules.ums.model.UmsRoleMenuRelation;
import com.macro.mall.tiny.modules.ums.model.UmsRoleResourceRelation;
import com.macro.mall.tiny.modules.ums.service.UmsAdminCacheService;
import com.macro.mall.tiny.modules.ums.service.UmsRoleMenuRelationService;
import com.macro.mall.tiny.modules.ums.service.UmsRoleResourceRelationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link UmsRoleServiceImpl}.
 *
 * <p>Roles tie admins to the menus they can see and the resources they can call.
 * The role/menu and role/resource allocation flows must always wipe existing
 * relations before inserting new ones, otherwise stale rows would silently leak
 * permissions.
 */
@ExtendWith(MockitoExtension.class)
class UmsRoleServiceImplTest {

    @Mock
    private UmsAdminCacheService adminCacheService;
    @Mock
    private UmsRoleMenuRelationService roleMenuRelationService;
    @Mock
    private UmsRoleResourceRelationService roleResourceRelationService;
    @Mock
    private UmsMenuMapper menuMapper;
    @Mock
    private UmsResourceMapper resourceMapper;
    @Mock
    private UmsRoleMapper roleMapper;

    @Spy
    @InjectMocks
    private UmsRoleServiceImpl service;

    @Test
    void should_initializeCountersAndPersist_when_creatingRole() {
        UmsRole role = new UmsRole();
        role.setName("ops");
        doReturn(true).when(service).save(role);

        boolean success = service.create(role);

        assertThat(success).isTrue();
        assertThat(role.getCreateTime()).isNotNull().isBeforeOrEqualTo(new Date());
        assertThat(role.getAdminCount())
                .as("a brand new role must start with zero admins")
                .isZero();
        assertThat(role.getSort()).isZero();
        verify(service).save(role);
    }

    @Test
    void should_removeRolesAndInvalidatePermissionCachesOfAffectedAdmins_when_deletingRoles() {
        List<Long> roleIds = Arrays.asList(1L, 2L, 3L);
        doReturn(true).when(service).removeByIds(roleIds);

        boolean success = service.delete(roleIds);

        assertThat(success).isTrue();
        verify(service).removeByIds(roleIds);
        verify(adminCacheService).delResourceListByRoleIds(roleIds);
    }

    @Test
    void should_returnFalseAndStillInvalidateCache_when_removeByIdsFails() {
        List<Long> roleIds = Collections.singletonList(99L);
        doReturn(false).when(service).removeByIds(roleIds);

        boolean success = service.delete(roleIds);

        assertThat(success).isFalse();
        verify(adminCacheService).delResourceListByRoleIds(roleIds);
    }

    @Test
    void should_pageRoles_when_keywordIsBlank() {
        Page<UmsRole> expected = new Page<>(1, 10);
        ArgumentCaptor<Page<UmsRole>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        doReturn(expected).when(service).page(pageCaptor.capture(), any(Wrapper.class));

        Page<UmsRole> result = service.list("", 10, 1);

        assertThat(result).isSameAs(expected);
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
    }

    @Test
    void should_pageRolesWithLikeFilter_when_keywordProvided() {
        Page<UmsRole> expected = new Page<>(2, 5);
        doReturn(expected).when(service).page(any(Page.class), any(Wrapper.class));

        Page<UmsRole> result = service.list("admin", 5, 2);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void should_delegateToMenuMapper_when_lookingUpMenusByAdminId() {
        List<UmsMenu> expected = Collections.singletonList(new UmsMenu());
        doReturn(expected).when(menuMapper).getMenuList(7L);

        List<UmsMenu> result = service.getMenuList(7L);

        assertThat(result).isSameAs(expected);
        verify(menuMapper).getMenuList(7L);
    }

    @Test
    void should_delegateToMenuMapper_when_listingMenusByRole() {
        List<UmsMenu> expected = Collections.singletonList(new UmsMenu());
        doReturn(expected).when(menuMapper).getMenuListByRoleId(7L);

        List<UmsMenu> result = service.listMenu(7L);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void should_delegateToResourceMapper_when_listingResourcesByRole() {
        List<UmsResource> expected = Collections.singletonList(new UmsResource());
        doReturn(expected).when(resourceMapper).getResourceListByRoleId(7L);

        List<UmsResource> result = service.listResource(7L);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void should_replaceAllMenuRelations_when_allocatingMenusToRole() {
        ArgumentCaptor<List<UmsRoleMenuRelation>> insertCaptor = ArgumentCaptor.forClass(List.class);

        int count = service.allocMenu(5L, Arrays.asList(10L, 11L, 12L));

        assertThat(count).isEqualTo(3);
        // existing relations are deleted before any new ones are written
        InOrder ordered = inOrder(roleMenuRelationService);
        ordered.verify(roleMenuRelationService).remove(any(Wrapper.class));
        ordered.verify(roleMenuRelationService).saveBatch(insertCaptor.capture());
        List<UmsRoleMenuRelation> inserted = insertCaptor.getValue();
        assertThat(inserted).hasSize(3);
        assertThat(inserted).allSatisfy(rel -> assertThat(rel.getRoleId()).isEqualTo(5L));
        assertThat(inserted).extracting(UmsRoleMenuRelation::getMenuId)
                .containsExactly(10L, 11L, 12L);
    }

    @Test
    void should_onlyDeleteOldRelations_when_allocatingEmptyMenuList() {
        int count = service.allocMenu(5L, Collections.emptyList());

        assertThat(count).isZero();
        verify(roleMenuRelationService).remove(any(Wrapper.class));
        verify(roleMenuRelationService).saveBatch(Collections.emptyList());
    }

    @Test
    void should_replaceResourceRelationsAndInvalidateCache_when_allocatingResourcesToRole() {
        ArgumentCaptor<List<UmsRoleResourceRelation>> insertCaptor = ArgumentCaptor.forClass(List.class);

        int count = service.allocResource(5L, Arrays.asList(20L, 21L));

        assertThat(count).isEqualTo(2);
        InOrder ordered = inOrder(roleResourceRelationService, adminCacheService);
        ordered.verify(roleResourceRelationService).remove(any(Wrapper.class));
        ordered.verify(roleResourceRelationService).saveBatch(insertCaptor.capture());
        // cache invalidation must happen *after* the writes, otherwise concurrent
        // requests could repopulate the cache from the old DB state
        ordered.verify(adminCacheService).delResourceListByRole(5L);

        List<UmsRoleResourceRelation> inserted = insertCaptor.getValue();
        assertThat(inserted).extracting(UmsRoleResourceRelation::getResourceId)
                .containsExactly(20L, 21L);
        assertThat(inserted).allSatisfy(rel -> assertThat(rel.getRoleId()).isEqualTo(5L));
    }

    @Test
    void should_notHitMenuRelationService_when_allocatingResources() {
        service.allocResource(5L, Collections.singletonList(20L));

        verify(roleMenuRelationService, never()).remove(any(Wrapper.class));
        verify(roleMenuRelationService, never()).saveBatch(any());
        verify(adminCacheService, never()).delResourceListByRoleIds(any());
        // sanity-check: roleId is what got passed to the cache invalidator
        verify(adminCacheService).delResourceListByRole(eq(5L));
    }
}
