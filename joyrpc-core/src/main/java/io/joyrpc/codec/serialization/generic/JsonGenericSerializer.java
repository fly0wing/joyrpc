package io.joyrpc.codec.serialization.generic;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.joyrpc.Plugin;
import io.joyrpc.codec.serialization.GenericSerializer;
import io.joyrpc.codec.serialization.Json;
import io.joyrpc.codec.serialization.UnsafeByteArrayInputStream;
import io.joyrpc.exception.CodecException;
import io.joyrpc.extension.Extension;
import io.joyrpc.protocol.message.Call;
import io.joyrpc.util.ClassUtils;
import io.joyrpc.util.GenericMethod;
import io.joyrpc.util.GenericType;

import java.lang.reflect.*;
import java.util.HashMap;
import java.util.Map;

/**
 * JSON序列化
 */
@Extension(GenericSerializer.JSON)
public class JsonGenericSerializer implements GenericSerializer {

    protected static final Object NULL = new Object();

    /**
     * JSON插件
     */
    protected final Json json = Plugin.JSON.get();

    @Override
    public Object serialize(final Object object) throws CodecException {
        return object == null || object instanceof Void ? null : json.toJSONBytes(object);
    }

    @Override
    public Object[] deserialize(final Call invocation) throws CodecException {
        GenericMethod genericMethod = invocation.getGenericMethod();
        Parameter[] parameters = invocation.getMethod().getParameters();
        Object[] genericArgs = invocation.getArgs();
        if (parameters.length == 0) {
            return new Object[0];
        } else {
            Object[] paramArgs = genericArgs == null || genericArgs.length < 3 ? null : (Object[]) genericArgs[2];
            String[] argTypes = genericArgs == null || genericArgs.length < 3 ? null : (String[]) genericArgs[1];
            byte[] json = paramArgs == null || paramArgs.length == 0 ? null : (byte[]) paramArgs[0];
            if (json == null || json.length == 0) {
                throw new CodecException("The number of parameter is wrong.");
            } else {
                switch (json[0]) {
                    case '[':
                        return parseArray(parameters, genericMethod.getParameters(), argTypes, json);
                    case '{':
                        return parseObject(parameters, genericMethod.getParameters(), argTypes, json);
                    default:
                        throw new CodecException("The content is not json format.");
                }
            }
        }
    }

    /**
     * 获取真实的参数类型
     *
     * @param genericTypes 参数泛化信息
     * @param argTypes     调用方指定的类型
     * @param index        参数索引
     * @param genericTypes 泛化参数类型
     * @return
     */
    protected Type getType(final GenericType[] genericTypes, final String[] argTypes, final int index) {
        Type type = genericTypes[index].getType();
        String argType = argTypes == null || argTypes.length <= index ? null : argTypes[index];
        if (argType != null && !argType.isEmpty()) {
            try {
                Class<?> aClass = ClassUtils.getClass(argType);
                if (validate(type, aClass, true)) {
                    //防止漏洞攻击
                    type = aClass;
                }
            } catch (ClassNotFoundException e) {
            }
        }
        return type;
    }

    /**
     * 验证类型，防止漏洞攻击
     *
     * @param type   目标类型
     * @param clazz  调用方指定的类
     * @param parent 判断目标类型可以被调用方指定的类赋值
     * @return 是否有效
     */
    protected boolean validate(final Type type, final Class<?> clazz, final boolean parent) {
        if (type instanceof Class) {
            if (parent ? ((Class) type).isAssignableFrom(clazz) : clazz.isAssignableFrom((Class) type)) {
                //防止漏洞攻击
                return true;
            }
        } else if (type instanceof GenericArrayType) {
            return validate(((GenericArrayType) type).getGenericComponentType(), clazz, parent);
        } else if (type instanceof ParameterizedType) {
            return validate(((ParameterizedType) type).getRawType(), clazz, parent);
        } else if (type instanceof TypeVariable) {
            Type[] bounds = ((TypeVariable) type).getBounds();
            if (parent && bounds != null) {
                for (Type t : bounds)
                    if (!validate(t, clazz, true)) {
                        return false;
                    }
            }
            return true;
        } else if (type instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) type).getUpperBounds();
            Type[] lowerBounds = ((WildcardType) type).getLowerBounds();
            if (parent && upperBounds != null) {
                for (Type t : upperBounds)
                    if (!validate(t, clazz, true)) {
                        return false;
                    }
            }
            if (parent && lowerBounds != null) {
                for (Type t : lowerBounds)
                    if (!validate(t, clazz, false)) {
                        return false;
                    }
            }
            return true;
        }
        return false;
    }

    /**
     * 解析数组
     *
     * @param parameters   参数对象
     * @param genericTypes 参数泛化信息
     * @param argTypes     客户端传递的参数
     * @param text         文本内容
     * @return
     */
    protected Object[] parseArray(final Parameter[] parameters,
                                  final GenericType[] genericTypes,
                                  final String[] argTypes,
                                  final byte[] text) {
        final int[] index = new int[]{0};
        final Object[] result = new Object[parameters.length];
        json.parseArray(new UnsafeByteArrayInputStream(text), o -> {
            if (index[0] < parameters.length) {
                result[index[0]] = o.apply(getType(genericTypes, argTypes, index[0]));
            } else {
                //忽略掉多余的参数
                o.apply(Object.class);
            }
            ++index[0];
            return true;
        });
        if (index[0] != parameters.length) {
            throw new CodecException("The number of parameter is wrong.");
        }
        return result;
    }


    /**
     * 解析数组
     *
     * @param parameters   参数对象
     * @param genericTypes 参数泛化信息
     * @param argTypes     客户端传递的参数
     * @param text         文本内容
     * @return
     */
    protected Object[] parseObject(final Parameter[] parameters,
                                   final GenericType[] genericTypes,
                                   final String[] argTypes,
                                   final byte[] text) {
        if (parameters.length == 1) {
            return new Object[]{json.parseObject(new UnsafeByteArrayInputStream(text),
                    getType(genericTypes, argTypes, 0))};
        }
        final int[] index = new int[]{0};
        final Object[] result = new Object[parameters.length];
        Map<String, Integer> names = new HashMap<>(parameters.length);
        Parameter parameter;
        for (int i = 0; i < parameters.length; i++) {
            parameter = parameters[i];
            if (parameter.isNamePresent()) {
                names.put(parameter.getName(), i);
                names.put("arg" + i, i);
            } else {
                names.put(parameter.getName(), i);
            }
        }
        json.parseObject(new UnsafeByteArrayInputStream(text), (k, o) -> {
            //根据名称获取参数位置
            Integer pos = names.get(k);
            if (pos != null) {
                result[pos] = o.apply(getType(genericTypes, argTypes, pos));
                //null设置为NULL对象
                if (result[pos] == null) {
                    result[pos] = NULL;
                }
            } else {
                //按照空位顺序占位
                for (int i = index[0]; i < parameters.length; i++) {
                    if (result[i] == null) {
                        result[i] = o.apply(getType(genericTypes, argTypes, i));
                        //null设置为NULL对象
                        if (result[i] == null) {
                            result[i] = NULL;
                        }
                        //递增
                        index[0] = i + 1;
                        break;
                    }
                }
            }
            return true;
        });
        //判断参数是否足够
        for (int i = 0; i < result.length; i++) {
            if (result[i] == null) {
                throw new CodecException("The number of parameter is wrong.");
            } else if (result[i] == NULL) {
                result[i] = null;
            }
        }
        return result;
    }

}
