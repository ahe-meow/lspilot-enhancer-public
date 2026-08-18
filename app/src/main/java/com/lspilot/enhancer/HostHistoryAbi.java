package com.lspilot.enhancer;

import java.lang.reflect.Method;
import java.util.List;

/** Resolves the current host repository seam used for destructive full-list saves. */
final class HostHistoryAbi {
    private static final String REPOSITORY_CLASS =
            "me.yun.lspilot.data.repository.b";

    final Method saveMethod;
    final Method readAllMethod;
    final Method databaseMethod;
    final Method daoMethod;
    final Method countMethod;

    private HostHistoryAbi(Method saveMethod, Method readAllMethod, Method databaseMethod,
            Method daoMethod, Method countMethod) {
        this.saveMethod = saveMethod;
        this.readAllMethod = readAllMethod;
        this.databaseMethod = databaseMethod;
        this.daoMethod = daoMethod;
        this.countMethod = countMethod;
    }

    static HostHistoryAbi resolve(ClassLoader loader) throws Exception {
        Class<?> repository = loader.loadClass(REPOSITORY_CLASS);
        Method save = required(repository, "r", void.class, String.class, List.class);
        Method readAll = required(repository, "i", List.class, String.class);
        Method database = required(repository, "h", null);
        Method dao = required(database.getReturnType(), "I", null);
        Method count = required(dao.getReturnType(), "o", int.class, String.class);
        return new HostHistoryAbi(save, readAll, database, dao, count);
    }

    int countRows(Object repository, String chatId) throws Exception {
        Object database = databaseMethod.invoke(repository);
        Object dao = daoMethod.invoke(database);
        return ((Number) countMethod.invoke(dao, chatId)).intValue();
    }

    @SuppressWarnings("unchecked")
    List<?> readAll(Object repository, String chatId) throws Exception {
        return (List<?>) readAllMethod.invoke(repository, chatId);
    }

    static Method messageIdMethod(List<?> preserved, List<?> current) throws Exception {
        Method method = findMessageIdMethod(preserved);
        if (method != null) return method;
        method = findMessageIdMethod(current);
        if (method == null) throw new NoSuchMethodException("host message id method missing");
        return method;
    }

    private static Method findMessageIdMethod(List<?> messages) throws Exception {
        if (messages == null) return null;
        for (Object message : messages) {
            if (message == null) continue;
            Method method = message.getClass().getDeclaredMethod("f");
            if (method.getReturnType() != String.class || method.getParameterTypes().length != 0) {
                throw new NoSuchMethodException("host message id ABI changed: " + method);
            }
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private static Method required(Class<?> owner, String name, Class<?> returnType,
            Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        if (returnType != null && method.getReturnType() != returnType) {
            throw new NoSuchMethodException("host history ABI return type changed: " + method);
        }
        method.setAccessible(true);
        return method;
    }
}
