package com.tong.ziwen.ai.dto;

/**
 * 娃档案（家长下的子账号）：对内对外的全部字段就是昵称一个。
 *
 * <p>不携带家长 id——列表接口本就只查"当前家长自己的娃"，
 * 响应里再回传归属字段没有意义，白占序列化体积。</p>
 */
public record ChildProfile(long id, String nickname) {
}
