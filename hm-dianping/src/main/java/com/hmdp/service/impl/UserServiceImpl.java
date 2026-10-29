package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.UserProfileUpdateDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.sms.SmsCodeService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;


import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static cn.hutool.core.util.RandomUtil.*;
import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Moon
 * @since 2026-07-13
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private SmsCodeService smsCodeService;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        return smsCodeService.sendCode(phone);
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1、校验手机号
        String phone=loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不正确");
        }
        //TODO 2、从Redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code=loginForm.getCode();
        if(cacheCode==null||!cacheCode.equals(code)){
            //3、不一致，报错
            return Result.fail("验证码错误");
        }
        //4、一致，根据手机号查询用户 select * from user where phone=?
        User user = query().eq("phone", phone).one();

        //5、判断用户是否存在
        if(user==null){
            //6、不存在，创建新用户并保存
            user=createUserWithPhone(phone);
        }

        // 7、保存用户信息到Redis当中。
        //7.1随机生成token作为登录令牌
        String token = UUID.randomUUID().toString(true);

        //7.2将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );

        //7.3存储
        String tokenKey=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.DAYS);

        //8、返回token给客户端
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        if (StrUtil.isBlank(token)) {
            return Result.ok();
        }
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public Result updateProfile(UserProfileUpdateDTO profile, String token) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null || currentUser.getId() == null) {
            return Result.fail("请先登录");
        }
        if (profile == null) {
            return Result.fail("资料不能为空");
        }

        Long userId = currentUser.getId();
        String nickName = StrUtil.trim(profile.getNickName());
        if (StrUtil.isBlank(nickName)) {
            return Result.fail("昵称不能为空");
        }
        if (nickName.length() > 32) {
            return Result.fail("昵称不能超过32个字符");
        }
        String introduce = StrUtil.trim(profile.getIntroduce());
        if (introduce != null && introduce.length() > 128) {
            return Result.fail("个人介绍不能超过128个字符");
        }

        User user = new User();
        user.setId(userId);
        user.setNickName(nickName);
        if (StrUtil.isNotBlank(profile.getIcon())) {
            user.setIcon(profile.getIcon());
        }
        updateById(user);

        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            info = new UserInfo();
            info.setUserId(userId);
        }
        info.setCity(StrUtil.blankToDefault(StrUtil.trim(profile.getCity()), "广州"));
        info.setIntroduce(introduce);
        info.setGender(profile.getGender());
        info.setBirthday(profile.getBirthday());
        userInfoService.saveOrUpdate(info);

        currentUser.setNickName(nickName);
        if (StrUtil.isNotBlank(profile.getIcon())) {
            currentUser.setIcon(profile.getIcon());
        }
        if (StrUtil.isNotBlank(token)) {
            String tokenKey = LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().put(tokenKey, "nickName", currentUser.getNickName());
            stringRedisTemplate.opsForHash().put(tokenKey, "icon", currentUser.getIcon() == null ? "" : currentUser.getIcon());
            stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.DAYS);
        }
        UserHolder.saveUser(currentUser);
        return Result.ok();
    }

    @Override
    public Result sign() {
        //1、获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        //2、获取日期
        LocalDateTime now=LocalDateTime.now();
        //3、拼接key
        String keySuffix=now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key=USER_SIGN_KEY+userId+":"+keySuffix;

        //4、获取今天是本月的第几天
        int dayOfMonth=now.getDayOfMonth();

        //5、写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        //6、返回
        return Result.ok();
    }

    @Override
    public Result signCount() {

        //1、获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        //2、获取日期
        LocalDateTime now=LocalDateTime.now();
        //3、拼接key
        String keySuffix=now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key=USER_SIGN_KEY+userId+":"+keySuffix;

        //4、获取今天是本月的第几天
        int dayOfMonth=now.getDayOfMonth();
        //5、获取本月截止今天位置所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result==null||result.isEmpty()){
            //没有任何签到结果
            return Result.ok(0);
        }
        Long num=result.get(0);
        if(num==null||num==0){
            return Result.ok(0);
        }
        //6、循环遍历
        int count=0;
        while(true){
            //6.1、让这个数字与1做与运算，得到数字的是最后一个bit位
            //判断这个bit位是否为0
            if((num&1)==0){
                //如果为0，说明未签到，结束
                break;
            }else{
                //如果不为0，说明已签到，计数器+1
                count++;
            }
            //把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num=num>>>=1;
        }

        return Result.ok(count);
    }

    /**
     * 根据手机号创建新用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        //创建用户
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        //保存用户
        save(user);
        return user;
    }
}
