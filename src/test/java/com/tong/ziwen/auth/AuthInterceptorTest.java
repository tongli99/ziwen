package com.tong.ziwen.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tong.ziwen.ai.controller.ChildController;
import com.tong.ziwen.ai.dto.ChildProfile;
import com.tong.ziwen.ai.service.UserService;
import com.tong.ziwen.ai.tool.ToolCallLimiter;
import com.tong.ziwen.auth.controller.AuthController;
import com.tong.ziwen.auth.model.ParentAccount;
import com.tong.ziwen.auth.service.AccountService;
import com.tong.ziwen.auth.service.TokenService;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * 登录边界测试：拦谁、放谁、放行时家长 id 传得对不对。
 *
 * <p>v0.5 隔离防线的第一半在认证边界——"家长 id 出自登录态、前端伪造不了"。
 * 这道边界又最容易被无声弄坏：白名单改一个路径、请求头换个名字，都不产生
 * 任何编译错误，改坏只能靠回归测试钉住。本类覆盖三种拒绝（无 token、
 * 方案不对、查无此 token）、三个白名单放行（注册/登录/注销），以及
 * "校验通过后 parentId 经 request attribute 落到 controller"的关键接线。</p>
 *
 * <p>用 {@code @WebMvcTest} 切片而非整包 {@code @SpringBootTest}：边界测试
 * 只该拉起它测的层（拦截器、白名单注册、全局异常处理、{@code @RequestAttribute}
 * 接线），模型、SQLite、Redis 一概不碰。TokenService 被 mock——拦截器的契约
 * 只到"校验通过给回家长 id"为止，token 存取的正确性另有 TokenServiceTest 把守。</p>
 */
@WebMvcTest(controllers = { AuthController.class, ChildController.class })
class AuthInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    /** 拦截器与两个 controller 的依赖全部换成替身：本类测边界，不测它们。 */
    @MockitoBean
    private TokenService tokenService;
    @MockitoBean
    private AccountService accountService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private ChatMemoryStore chatMemoryStore;
    @MockitoBean
    private ToolCallLimiter toolCallLimiter;

    @Test
    void protectedApiRejectsMissingToken() throws Exception {
        // 空手访问受保护接口：401 + code=1，前端凭 401 跳登录页
        mockMvc.perform(get("/api/children"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    @Test
    void protectedApiRejectsNonBearerScheme() throws Exception {
        // 方案不是 Bearer 直接拒绝，且不去查存储——scheme 解析在 token 校验之前
        mockMvc.perform(get("/api/children")
                        .header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1));

        verify(tokenService, never()).parentIdOf(anyString());
    }

    @Test
    void protectedApiRejectsUnknownToken() throws Exception {
        // 查无此 token：与没登录同一个 401、同一句文案，不给探测者区分信号
        when(tokenService.parentIdOf("no-such-token")).thenReturn(null);

        mockMvc.perform(get("/api/children")
                        .header("Authorization", "Bearer no-such-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    @Test
    void validTokenPassesParentIdDownstream() throws Exception {
        // 放行后的关键接线：拦截器解出的家长 id 经 request attribute 传到 controller，
        // controller 只认它、不认请求参数——"身份可信"的最后一跳
        when(tokenService.parentIdOf("good-token")).thenReturn(42L);
        when(userService.listChildren(42L)).thenReturn(List.of(new ChildProfile(1L, "老大")));

        mockMvc.perform(get("/api/children")
                        .header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nickname").value("老大"));

        verify(userService).listChildren(42L);
    }

    @Test
    void registerWhitelistedWithoutToken() throws Exception {
        // 白名单：注册本身不需要登录态——token 是注册成功后才签发给它的
        when(accountService.register("dad", "secret-123"))
                .thenReturn(new ParentAccount(1L, "dad", "bcrypt-hash"));
        when(tokenService.issueToken(1L)).thenReturn("issued-token");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dad\",\"password\":\"secret-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("issued-token"));

        // 白名单入口从头到尾不做 token 校验
        verify(tokenService, never()).parentIdOf(anyString());
    }

    @Test
    void loginWhitelistedWithoutToken() throws Exception {
        when(accountService.login("dad", "secret-123")).thenReturn(42L);
        when(tokenService.issueToken(42L)).thenReturn("issued-token");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dad\",\"password\":\"secret-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("issued-token"));
    }

    @Test
    void logoutWhitelistedWithoutToken() throws Exception {
        // 注销幂等放行：没登录就点注销，也回成功而不是 401
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
