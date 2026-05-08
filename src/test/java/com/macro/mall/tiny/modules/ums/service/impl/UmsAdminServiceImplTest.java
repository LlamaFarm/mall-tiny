package com.macro.mall.tiny.modules.ums.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.macro.mall.tiny.common.exception.ApiException;
import com.macro.mall.tiny.domain.AdminUserDetails;
import com.macro.mall.tiny.modules.ums.dto.UmsAdminParam;
import com.macro.mall.tiny.modules.ums.dto.UpdateAdminPasswordParam;
import com.macro.mall.tiny.modules.ums.mapper.UmsAdminLoginLogMapper;
import com.macro.mall.tiny.modules.ums.mapper.UmsAdminMapper;
import com.macro.mall.tiny.modules.ums.mapper.UmsResourceMapper;
import com.macro.mall.tiny.modules.ums.mapper.UmsRoleMapper;
import com.macro.mall.tiny.modules.ums.model.UmsAdmin;
import com.macro.mall.tiny.modules.ums.model.UmsAdminLoginLog;
import com.macro.mall.tiny.modules.ums.model.UmsAdminRoleRelation;
import com.macro.mall.tiny.modules.ums.model.UmsResource;
import com.macro.mall.tiny.modules.ums.model.UmsRole;
import com.macro.mall.tiny.modules.ums.service.UmsAdminCacheService;
import com.macro.mall.tiny.modules.ums.service.UmsAdminRoleRelationService;
import com.macro.mall.tiny.security.util.JwtTokenUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UmsAdminServiceImpl}.
 *
 * <p>This is the heart of the UMS service layer: it owns user CRUD, login,
 * password rotation, role assignment, and resource lookup. The implementation
 * leans on three subtle behaviors we lock down here:
 * <ul>
 *   <li>{@code getCacheService()} is a Spring-bean indirection wrapped in a
 *       method, so we stub it on the spy rather than mocking the static
 *       {@code SpringUtil} lookup;</li>
 *   <li>caches must be invalidated on every mutation that affects either the
 *       admin record or its derived resource list;</li>
 *   <li>login swallows {@link org.springframework.security.core.AuthenticationException}
 *       but lets {@link ApiException} (wrong password / disabled account) bubble
 *       up to the controller layer.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UmsAdminServiceImplTest {

    @Mock
    private JwtTokenUtil jwtTokenUtil;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UmsAdminLoginLogMapper loginLogMapper;
    @Mock
    private UmsAdminRoleRelationService adminRoleRelationService;
    @Mock
    private UmsRoleMapper roleMapper;
    @Mock
    private UmsResourceMapper resourceMapper;
    @Mock
    private UmsAdminMapper baseMapper;
    @Mock
    private UmsAdminCacheService adminCacheService;

    @Spy
    @InjectMocks
    private UmsAdminServiceImpl service;

    @BeforeEach
    void redirectCacheServiceLookup() {
        // The implementation reaches into Spring via SpringUtil.getBean(...). Stubbing
        // the wrapper method keeps the test free of static mocking machinery while
        // still giving us a mock cache to assert on. lenient() because some tests
        // never touch the cache (e.g. refreshToken, list, register-duplicate paths).
        Mockito.lenient().doReturn(adminCacheService).when(service).getCacheService();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static UmsAdmin admin(Long id, String username, String encodedPassword, Integer status) {
        UmsAdmin admin = new UmsAdmin();
        admin.setId(id);
        admin.setUsername(username);
        admin.setPassword(encodedPassword);
        admin.setStatus(status);
        return admin;
    }

    // -----------------------------------------------------------------------
    // getAdminByUsername
    // -----------------------------------------------------------------------

    @Test
    void should_returnCachedAdminWithoutHittingDb_when_cacheHits() {
        UmsAdmin cached = admin(1L, "alice", "enc", 1);
        when(adminCacheService.getAdmin("alice")).thenReturn(cached);

        UmsAdmin result = service.getAdminByUsername("alice");

        assertThat(result).isSameAs(cached);
        verify(service, never()).list(any(Wrapper.class));
        verify(adminCacheService, never()).setAdmin(any());
    }

    @Test
    void should_loadFromDbAndPopulateCache_when_cacheMissesAndAdminExists() {
        UmsAdmin fromDb = admin(1L, "alice", "enc", 1);
        when(adminCacheService.getAdmin("alice")).thenReturn(null);
        doReturn(Collections.singletonList(fromDb)).when(service).list(any(Wrapper.class));

        UmsAdmin result = service.getAdminByUsername("alice");

        assertThat(result).isSameAs(fromDb);
        verify(adminCacheService).setAdmin(fromDb);
    }

    @Test
    void should_returnNull_when_adminNotInCacheOrDb() {
        when(adminCacheService.getAdmin("ghost")).thenReturn(null);
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        UmsAdmin result = service.getAdminByUsername("ghost");

        assertThat(result).isNull();
        verify(adminCacheService, never()).setAdmin(any());
    }

    // -----------------------------------------------------------------------
    // register
    // -----------------------------------------------------------------------

    @Test
    void should_returnNull_when_registeringDuplicateUsername() {
        UmsAdminParam param = new UmsAdminParam();
        param.setUsername("alice");
        param.setPassword("plain");
        doReturn(Collections.singletonList(admin(1L, "alice", "enc", 1)))
                .when(service).list(any(Wrapper.class));

        UmsAdmin result = service.register(param);

        assertThat(result).isNull();
        verify(passwordEncoder, never()).encode(anyString());
        verify(baseMapper, never()).insert(any());
    }

    @Test
    void should_encodePasswordAndPersist_when_registeringFreshUser() {
        UmsAdminParam param = new UmsAdminParam();
        param.setUsername("bob");
        param.setPassword("plain");
        param.setEmail("bob@example.com");
        param.setNickName("Bobby");
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        when(passwordEncoder.encode("plain")).thenReturn("encoded");
        when(baseMapper.insert(any(UmsAdmin.class))).thenReturn(1);

        UmsAdmin result = service.register(param);

        ArgumentCaptor<UmsAdmin> insertCaptor = ArgumentCaptor.forClass(UmsAdmin.class);
        verify(baseMapper).insert(insertCaptor.capture());
        UmsAdmin inserted = insertCaptor.getValue();
        assertThat(result).isSameAs(inserted);
        assertThat(inserted.getUsername()).isEqualTo("bob");
        assertThat(inserted.getPassword())
                .as("password must be hashed before reaching the DB")
                .isEqualTo("encoded");
        assertThat(inserted.getEmail()).isEqualTo("bob@example.com");
        assertThat(inserted.getNickName()).isEqualTo("Bobby");
        assertThat(inserted.getStatus())
                .as("freshly registered accounts must default to enabled (status=1)")
                .isEqualTo(1);
        assertThat(inserted.getCreateTime()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // login
    // -----------------------------------------------------------------------

    @Test
    void should_returnGeneratedToken_when_credentialsValid() {
        // bind a mock servlet request so insertLoginLog can read the remote addr
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        UmsAdmin admin = admin(1L, "alice", "encoded", 1);
        AdminUserDetails userDetails = new AdminUserDetails(admin, Collections.emptyList());
        doReturn(userDetails).when(service).loadUserByUsername("alice");
        when(passwordEncoder.matches("plain", "encoded")).thenReturn(true);
        when(jwtTokenUtil.generateToken(userDetails)).thenReturn("jwt-token");
        // insertLoginLog calls getAdminByUsername back through this service
        doReturn(admin).when(service).getAdminByUsername("alice");

        String token = service.login("alice", "plain");

        assertThat(token).isEqualTo("jwt-token");
        ArgumentCaptor<UmsAdminLoginLog> logCaptor = ArgumentCaptor.forClass(UmsAdminLoginLog.class);
        verify(loginLogMapper).insert(logCaptor.capture());
        UmsAdminLoginLog log = logCaptor.getValue();
        assertThat(log.getAdminId()).isEqualTo(1L);
        assertThat(log.getIp()).isEqualTo("10.0.0.5");
        assertThat(log.getCreateTime()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("a successful login must seed the SecurityContext for downstream filters")
                .isNotNull();
    }

    @Test
    void should_returnNull_when_loadUserByUsernameThrowsAuthenticationException() {
        // Force loadUserByUsername to raise UsernameNotFoundException so that
        // login()'s AuthenticationException catch block returns null instead of
        // a token.
        doThrow(new UsernameNotFoundException("nope"))
                .when(service).loadUserByUsername("ghost");

        String token = service.login("ghost", "any");

        assertThat(token).isNull();
        verifyNoInteractions(loginLogMapper);
        verifyNoInteractions(jwtTokenUtil);
    }

    @Test
    void should_propagateApiException_when_passwordDoesNotMatch() {
        UmsAdmin admin = admin(1L, "alice", "encoded", 1);
        AdminUserDetails userDetails = new AdminUserDetails(admin, Collections.emptyList());
        doReturn(userDetails).when(service).loadUserByUsername("alice");
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> service.login("alice", "wrong"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("密码不正确");
        verifyNoInteractions(jwtTokenUtil);
        verifyNoInteractions(loginLogMapper);
    }

    @Test
    void should_propagateApiException_when_accountDisabled() {
        UmsAdmin disabled = admin(2L, "carol", "encoded", 0);
        AdminUserDetails userDetails = new AdminUserDetails(disabled, Collections.emptyList());
        doReturn(userDetails).when(service).loadUserByUsername("carol");
        when(passwordEncoder.matches("plain", "encoded")).thenReturn(true);

        assertThatThrownBy(() -> service.login("carol", "plain"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("帐号已被禁用");
        verifyNoInteractions(jwtTokenUtil);
        verifyNoInteractions(loginLogMapper);
    }

    // -----------------------------------------------------------------------
    // refreshToken
    // -----------------------------------------------------------------------

    @Test
    void should_delegateToJwtTokenUtil_when_refreshingToken() {
        when(jwtTokenUtil.refreshHeadToken("Bearer abc")).thenReturn("Bearer xyz");

        String refreshed = service.refreshToken("Bearer abc");

        assertThat(refreshed).isEqualTo("Bearer xyz");
    }

    // -----------------------------------------------------------------------
    // list
    // -----------------------------------------------------------------------

    @Test
    void should_pageAdminsWithoutFilter_when_keywordIsBlank() {
        Page<UmsAdmin> expected = new Page<>(1, 10);
        ArgumentCaptor<Page<UmsAdmin>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        doReturn(expected).when(service).page(pageCaptor.capture(), any(Wrapper.class));

        Page<UmsAdmin> result = service.list("", 10, 1);

        assertThat(result).isSameAs(expected);
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
    }

    @Test
    void should_pageAdminsWithLikeFilter_when_keywordProvided() {
        Page<UmsAdmin> expected = new Page<>(2, 5);
        doReturn(expected).when(service).page(any(Page.class), any(Wrapper.class));

        Page<UmsAdmin> result = service.list("ali", 5, 2);

        assertThat(result).isSameAs(expected);
    }

    // -----------------------------------------------------------------------
    // update
    // -----------------------------------------------------------------------

    @Test
    void should_skipPasswordEncoding_when_submittedPasswordEqualsStoredHash() {
        UmsAdmin existing = admin(1L, "alice", "stored-hash", 1);
        UmsAdmin update = new UmsAdmin();
        update.setPassword("stored-hash");
        update.setNickName("Alice2");
        doReturn(existing).when(service).getById(1L);
        doReturn(true).when(service).updateById(update);

        boolean ok = service.update(1L, update);

        assertThat(ok).isTrue();
        assertThat(update.getId()).isEqualTo(1L);
        assertThat(update.getPassword())
                .as("if the form field still holds the old hash, don't re-hash it")
                .isNull();
        verify(passwordEncoder, never()).encode(anyString());
        verify(adminCacheService).delAdmin(1L);
    }

    @Test
    void should_encodeNewPassword_when_submittedPasswordDiffers() {
        UmsAdmin existing = admin(1L, "alice", "old-hash", 1);
        UmsAdmin update = new UmsAdmin();
        update.setPassword("new-plain");
        doReturn(existing).when(service).getById(1L);
        when(passwordEncoder.encode("new-plain")).thenReturn("new-hash");
        doReturn(true).when(service).updateById(update);

        service.update(1L, update);

        assertThat(update.getPassword()).isEqualTo("new-hash");
        verify(adminCacheService).delAdmin(1L);
    }

    @Test
    void should_blankOutPassword_when_submittedPasswordIsEmpty() {
        UmsAdmin existing = admin(1L, "alice", "old-hash", 1);
        UmsAdmin update = new UmsAdmin();
        update.setPassword("");
        doReturn(existing).when(service).getById(1L);
        doReturn(true).when(service).updateById(update);

        service.update(1L, update);

        assertThat(update.getPassword())
                .as("blank password input must NOT overwrite the stored hash")
                .isNull();
        verify(passwordEncoder, never()).encode(anyString());
    }

    // -----------------------------------------------------------------------
    // delete
    // -----------------------------------------------------------------------

    @Test
    void should_invalidateAdminAndResourceCaches_when_deletingAdmin() {
        doReturn(true).when(service).removeById(7L);

        boolean ok = service.delete(7L);

        assertThat(ok).isTrue();
        verify(adminCacheService).delAdmin(7L);
        verify(adminCacheService).delResourceList(7L);
        verify(service).removeById(7L);
    }

    // -----------------------------------------------------------------------
    // updateRole
    // -----------------------------------------------------------------------

    @Test
    void should_replaceRolesAndInvalidatePermissionCache_when_updateRoleCalled() {
        ArgumentCaptor<List<UmsAdminRoleRelation>> insertCaptor = ArgumentCaptor.forClass(List.class);

        int count = service.updateRole(5L, Arrays.asList(10L, 11L));

        assertThat(count).isEqualTo(2);
        verify(adminRoleRelationService).remove(any(Wrapper.class));
        verify(adminRoleRelationService).saveBatch(insertCaptor.capture());
        List<UmsAdminRoleRelation> inserted = insertCaptor.getValue();
        assertThat(inserted).hasSize(2);
        assertThat(inserted).allSatisfy(r -> assertThat(r.getAdminId()).isEqualTo(5L));
        assertThat(inserted).extracting(UmsAdminRoleRelation::getRoleId).containsExactly(10L, 11L);
        verify(adminCacheService).delResourceList(5L);
    }

    @Test
    void should_returnZeroAndSkipInsert_when_updateRoleReceivesNullList() {
        int count = service.updateRole(5L, null);

        assertThat(count).isZero();
        verify(adminRoleRelationService).remove(any(Wrapper.class));
        verify(adminRoleRelationService, never()).saveBatch(anyList());
        verify(adminCacheService).delResourceList(5L);
    }

    @Test
    void should_returnZeroAndSkipInsert_when_updateRoleReceivesEmptyList() {
        int count = service.updateRole(5L, Collections.emptyList());

        assertThat(count).isZero();
        verify(adminRoleRelationService).remove(any(Wrapper.class));
        verify(adminRoleRelationService, never()).saveBatch(anyList());
        verify(adminCacheService).delResourceList(5L);
    }

    // -----------------------------------------------------------------------
    // getRoleList
    // -----------------------------------------------------------------------

    @Test
    void should_delegateToRoleMapper_when_lookingUpRolesForAdmin() {
        List<UmsRole> expected = Collections.singletonList(new UmsRole());
        when(roleMapper.getRoleList(7L)).thenReturn(expected);

        List<UmsRole> result = service.getRoleList(7L);

        assertThat(result).isSameAs(expected);
    }

    // -----------------------------------------------------------------------
    // getResourceList
    // -----------------------------------------------------------------------

    @Test
    void should_returnCachedResources_when_cacheHasEntry() {
        List<UmsResource> cached = Collections.singletonList(new UmsResource());
        when(adminCacheService.getResourceList(7L)).thenReturn(cached);

        List<UmsResource> result = service.getResourceList(7L);

        assertThat(result).isSameAs(cached);
        verify(resourceMapper, never()).getResourceList(any());
        verify(adminCacheService, never()).setResourceList(any(), any());
    }

    @Test
    void should_loadFromDbAndCache_when_cacheMissesAndDbHasResources() {
        List<UmsResource> fromDb = Collections.singletonList(new UmsResource());
        when(adminCacheService.getResourceList(7L)).thenReturn(null);
        when(resourceMapper.getResourceList(7L)).thenReturn(fromDb);

        List<UmsResource> result = service.getResourceList(7L);

        assertThat(result).isSameAs(fromDb);
        verify(adminCacheService).setResourceList(7L, fromDb);
    }

    @Test
    void should_returnEmptyAndSkipCacheWrite_when_cacheMissesAndDbHasNothing() {
        when(adminCacheService.getResourceList(7L)).thenReturn(null);
        when(resourceMapper.getResourceList(7L)).thenReturn(Collections.emptyList());

        List<UmsResource> result = service.getResourceList(7L);

        assertThat(result).isEmpty();
        verify(adminCacheService, never()).setResourceList(any(), any());
    }

    // -----------------------------------------------------------------------
    // updatePassword
    // -----------------------------------------------------------------------

    @Test
    void should_returnNegativeOne_when_anyRequiredFieldIsBlank() {
        UpdateAdminPasswordParam blankUser = new UpdateAdminPasswordParam();
        blankUser.setUsername("");
        blankUser.setOldPassword("o");
        blankUser.setNewPassword("n");
        UpdateAdminPasswordParam blankOld = new UpdateAdminPasswordParam();
        blankOld.setUsername("u");
        blankOld.setOldPassword("");
        blankOld.setNewPassword("n");
        UpdateAdminPasswordParam blankNew = new UpdateAdminPasswordParam();
        blankNew.setUsername("u");
        blankNew.setOldPassword("o");
        blankNew.setNewPassword("");

        assertThat(service.updatePassword(blankUser)).isEqualTo(-1);
        assertThat(service.updatePassword(blankOld)).isEqualTo(-1);
        assertThat(service.updatePassword(blankNew)).isEqualTo(-1);
        verify(service, never()).list(any(Wrapper.class));
    }

    @Test
    void should_returnNegativeTwo_when_userNotFound() {
        UpdateAdminPasswordParam param = new UpdateAdminPasswordParam();
        param.setUsername("ghost");
        param.setOldPassword("o");
        param.setNewPassword("n");
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        assertThat(service.updatePassword(param)).isEqualTo(-2);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void should_returnNegativeThree_when_oldPasswordDoesNotMatch() {
        UpdateAdminPasswordParam param = new UpdateAdminPasswordParam();
        param.setUsername("alice");
        param.setOldPassword("wrong");
        param.setNewPassword("new");
        UmsAdmin admin = admin(1L, "alice", "stored-hash", 1);
        doReturn(Collections.singletonList(admin)).when(service).list(any(Wrapper.class));
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        assertThat(service.updatePassword(param)).isEqualTo(-3);
        verify(passwordEncoder, never()).encode(anyString());
        verifyNoInteractions(adminCacheService);
    }

    @Test
    void should_returnOneAndRotatePassword_when_credentialsMatch() {
        UpdateAdminPasswordParam param = new UpdateAdminPasswordParam();
        param.setUsername("alice");
        param.setOldPassword("old");
        param.setNewPassword("new");
        UmsAdmin admin = admin(1L, "alice", "stored-hash", 1);
        doReturn(Collections.singletonList(admin)).when(service).list(any(Wrapper.class));
        when(passwordEncoder.matches("old", "stored-hash")).thenReturn(true);
        when(passwordEncoder.encode("new")).thenReturn("new-hash");
        doReturn(true).when(service).updateById(admin);

        int code = service.updatePassword(param);

        assertThat(code).isEqualTo(1);
        assertThat(admin.getPassword())
                .as("the stored entity must carry the new hash before being persisted")
                .isEqualTo("new-hash");
        verify(service).updateById(admin);
        verify(adminCacheService).delAdmin(1L);
    }

    // -----------------------------------------------------------------------
    // loadUserByUsername
    // -----------------------------------------------------------------------

    @Test
    void should_throwUsernameNotFound_when_loadUserByUsernameMissesEverywhere() {
        when(adminCacheService.getAdmin("ghost")).thenReturn(null);
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void should_buildAdminUserDetailsWithResources_when_loadUserByUsernameHits() {
        UmsAdmin found = admin(1L, "alice", "encoded", 1);
        UmsResource resource = new UmsResource();
        resource.setId(99L);
        resource.setName("brand:read");
        when(adminCacheService.getAdmin("alice")).thenReturn(found);
        when(adminCacheService.getResourceList(1L))
                .thenReturn(Collections.singletonList(resource));

        UserDetails details = service.loadUserByUsername("alice");

        assertThat(details).isInstanceOf(AdminUserDetails.class);
        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getPassword()).isEqualTo("encoded");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
                .as("authorities must be derived from the admin's resource list")
                .hasSize(1)
                .extracting(Object::toString)
                .containsExactly("99:brand:read");
        // we do not look up the resource list a second time per call
        verify(adminCacheService, times(1)).getResourceList(1L);
    }

    // -----------------------------------------------------------------------
    // sanity: the @InjectMocks pattern wires the mapper into ServiceImpl#baseMapper
    // -----------------------------------------------------------------------

    @Test
    void should_useInjectedMapperAsBaseMapper_when_serviceCallsBaseMapperDirectly() {
        // register reaches into baseMapper.insert(...) directly; verifying that path
        // ensures @InjectMocks correctly wired the @Mock UmsAdminMapper into the
        // protected ServiceImpl#baseMapper field via type matching
        UmsAdminParam param = new UmsAdminParam();
        param.setUsername("dave");
        param.setPassword("plain");
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        when(passwordEncoder.encode("plain")).thenReturn("hashed");
        when(baseMapper.insert(any(UmsAdmin.class))).thenReturn(1);

        UmsAdmin result = service.register(param);

        assertThat(result).isNotNull();
        verify(baseMapper).insert(any(UmsAdmin.class));
    }
}
