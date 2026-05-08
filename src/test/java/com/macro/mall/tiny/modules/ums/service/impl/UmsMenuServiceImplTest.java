package com.macro.mall.tiny.modules.ums.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.macro.mall.tiny.modules.ums.dto.UmsMenuNode;
import com.macro.mall.tiny.modules.ums.mapper.UmsMenuMapper;
import com.macro.mall.tiny.modules.ums.model.UmsMenu;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link UmsMenuServiceImpl}.
 *
 * <p>Menus form a parent/child hierarchy where {@code level} is derived from the
 * parent's level. These tests pin down the level-derivation logic and the
 * tree-building used by the admin UI.
 */
@ExtendWith(MockitoExtension.class)
class UmsMenuServiceImplTest {

    @Mock
    private UmsMenuMapper menuMapper;

    @Spy
    @InjectMocks
    private UmsMenuServiceImpl service;

    private static UmsMenu menu(Long id, Long parentId, Integer level) {
        UmsMenu menu = new UmsMenu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setLevel(level);
        return menu;
    }

    @Test
    void should_setLevelToZero_when_creatingTopLevelMenu() {
        UmsMenu menu = new UmsMenu();
        menu.setParentId(0L);
        doReturn(true).when(service).save(menu);

        boolean success = service.create(menu);

        assertThat(success).isTrue();
        assertThat(menu.getLevel()).isZero();
        assertThat(menu.getCreateTime()).isNotNull().isBeforeOrEqualTo(new Date());
        verify(service, never()).getById(any());
    }

    @Test
    void should_deriveLevelFromParent_when_creatingChildMenu() {
        UmsMenu parent = menu(5L, 0L, 2);
        UmsMenu child = new UmsMenu();
        child.setParentId(5L);
        doReturn(parent).when(service).getById(5L);
        doReturn(true).when(service).save(child);

        service.create(child);

        assertThat(child.getLevel())
                .as("child level must always be parent.level + 1")
                .isEqualTo(3);
    }

    @Test
    void should_fallBackToLevelZero_when_parentMenuMissing() {
        UmsMenu orphan = new UmsMenu();
        orphan.setParentId(99L);
        doReturn(null).when(service).getById(99L);
        doReturn(true).when(service).save(orphan);

        service.create(orphan);

        assertThat(orphan.getLevel())
                .as("missing parent must not throw; treat as top-level")
                .isZero();
    }

    @Test
    void should_assignIdAndRecomputeLevel_when_updatingMenu() {
        UmsMenu menu = new UmsMenu();
        menu.setParentId(0L);
        doReturn(true).when(service).updateById(menu);

        boolean success = service.update(11L, menu);

        assertThat(success).isTrue();
        assertThat(menu.getId()).isEqualTo(11L);
        assertThat(menu.getLevel()).isZero();
    }

    @Test
    void should_pageMenusUnderParent_when_listing() {
        Page<UmsMenu> expected = new Page<>(1, 20);
        ArgumentCaptor<Page<UmsMenu>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        doReturn(expected).when(service).page(pageCaptor.capture(), any(Wrapper.class));

        Page<UmsMenu> result = service.list(2L, 20, 1);

        assertThat(result).isSameAs(expected);
        Page<UmsMenu> passedPage = pageCaptor.getValue();
        assertThat(passedPage.getCurrent()).isEqualTo(1);
        assertThat(passedPage.getSize()).isEqualTo(20);
    }

    @Test
    void should_buildNestedTree_when_treeListCalled() {
        UmsMenu root1 = menu(1L, 0L, 0);
        UmsMenu root2 = menu(2L, 0L, 0);
        UmsMenu childOfRoot1 = menu(3L, 1L, 1);
        UmsMenu grandChild = menu(4L, 3L, 2);
        List<UmsMenu> all = Arrays.asList(root1, root2, childOfRoot1, grandChild);
        doReturn(all).when(service).list();

        List<UmsMenuNode> tree = service.treeList();

        assertThat(tree).hasSize(2);
        UmsMenuNode firstRoot = tree.stream().filter(n -> n.getId().equals(1L)).findFirst().orElseThrow();
        UmsMenuNode secondRoot = tree.stream().filter(n -> n.getId().equals(2L)).findFirst().orElseThrow();
        assertThat(secondRoot.getChildren()).isEmpty();
        assertThat(firstRoot.getChildren()).hasSize(1);
        UmsMenuNode child = firstRoot.getChildren().get(0);
        assertThat(child.getId()).isEqualTo(3L);
        assertThat(child.getChildren()).hasSize(1);
        assertThat(child.getChildren().get(0).getId()).isEqualTo(4L);
        assertThat(child.getChildren().get(0).getChildren()).isEmpty();
    }

    @Test
    void should_persistOnlyHiddenFlag_when_togglingVisibility() {
        ArgumentCaptor<UmsMenu> captor = ArgumentCaptor.forClass(UmsMenu.class);
        doReturn(true).when(service).updateById(captor.capture());

        boolean success = service.updateHidden(8L, 1);

        assertThat(success).isTrue();
        UmsMenu updated = captor.getValue();
        assertThat(updated.getId()).isEqualTo(8L);
        assertThat(updated.getHidden()).isEqualTo(1);
        assertThat(updated.getTitle())
                .as("only id+hidden should be set; other columns must remain untouched")
                .isNull();
        assertThat(updated.getName()).isNull();
    }
}
