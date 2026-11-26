package com.tong.ziwen.auth.model;

/**
 * 家长账号：对外的字段就是 id 与用户名，passwordHash 仅供业务层内部核对，
 * 不随任何接口响应外出。
 */
public record ParentAccount(long id, String username, String passwordHash) {
}