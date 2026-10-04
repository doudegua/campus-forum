import { defineStore } from "pinia";

export const useStore = defineStore('general', {
    state: () => {
        return {
            user: {
                // 后端 /api/user/info 返回的字段是 registrationDate，不是 registerTime。
                // 这里原来写的是 registerTime，于是 UserSettings 里
                // new Date(undefined) → Invalid Date，设置页那行注册时间一直是坏的。
                // 字段名必须和后端 VO 一模一样，这种错编译期没人管。
                id: null,
                username: '',
                email: '',
                role: '',
                registrationDate: null
            },
            profile: {
                gender: '',
                phone: '',
                qq: '',
                // wechat: '',
                description: '',
                avatarUrl: ''
            },
            avatar: ''
        }
    }
});