# -*- coding: utf-8 -*-
"""
生成商品表种子数据 SQL：10 个分类 x 10 款 = 100 条商品。

用法：
    python tools/generate_product_seed.py

输出：
    src/main/resources/sql/product-seed-100.sql

数据规则：
- id 从 5 开始（1~4 为 schema.sql 自带的示例商品），显式指定，可重复执行前先删掉这批。
- 每个分类 10 款，其中 1 款为下架/缺货状态，方便测试上下架与零库存分支。
- image_url 复用项目既有的文生图服务，prompt 为 URL 编码后的英文描述。
"""

import os
import urllib.parse

# (名称, 描述, 价格, 库存, 状态, 图片 prompt)
CATEGORIES = {
    "数码配件": [
        ("65W 氮化镓充电器", "三口输出、PD3.0 快充，兼容笔记本/手机/平板，折叠插脚便携", 129.00, 300, 1, "white gan fast charger with three ports on white background product photography"),
        ("10000mAh 移动电源", "双向快充、数显电量、自带 Type-C 线，可上飞机", 99.00, 500, 1, "slim black power bank with digital display product photography"),
        ("Type-C 数据线 1.5m", "60W 快充、尼龙编织、弯头设计，支持数据传输", 29.90, 800, 1, "braided usb-c cable coiled on white background product photography"),
        ("15W 无线充电板", "Qi 协议、异物检测、静音散热，带壳也能充", 79.00, 260, 1, "round wireless charging pad on white background product photography"),
        ("便携蓝牙音箱", "IPX7 防水、360° 环绕、18 小时续航，户外露营必备", 199.00, 150, 1, "portable bluetooth speaker with fabric cover product photography"),
        ("多口 USB 扩展坞", "五合一 HDMI+PD+USB3.0，4K 投屏一线连接", 169.00, 180, 1, "aluminum usb-c hub with multiple ports product photography"),
        ("手机云台稳定器", "三轴防抖、智能跟随、可折叠收纳，Vlog 拍摄神器", 499.00, 80, 1, "smartphone gimbal stabilizer on white background product photography"),
        ("磁吸车载支架", "强磁吸附、出风口固定、单手取放，行车导航更稳", 49.00, 400, 1, "magnetic car phone mount on dashboard product photography"),
        ("降噪睡眠耳塞", "物理隔音 32dB、硅胶亲肤、侧睡不压耳", 59.00, 0, 0, "soft silicone sleep earplugs in case product photography"),
        ("桌面显示器增高架", "实木+钢架、带抽屉收纳，抬高视线护颈椎", 89.00, 120, 1, "wooden monitor stand riser with drawer product photography"),
    ],
    "智能穿戴": [
        ("智能运动手环 8", "1.47 寸全面屏、血氧心率、14 天续航、50 米防水", 249.00, 220, 1, "black fitness tracker band with colorful screen product photography"),
        ("儿童电话手表", "4G 全网通、GPS 定位、视频通话、上课免打扰", 399.00, 160, 1, "colorful kids smartwatch with gps product photography"),
        ("音频智能眼镜", "开放式双扬、偏光镜片、触控切歌，通勤骑行两用", 599.00, 90, 1, "modern audio sunglasses on white background product photography"),
        ("运动心率臂带", "光学心率监测、IP67 防水，跑步骑行实时播报", 269.00, 140, 1, "optical heart rate armband for running product photography"),
        ("智能健康戒指", "睡眠/体温/心率全天候监测，钛合金轻至 4 克", 899.00, 60, 1, "titanium smart health ring product photography"),
        ("老人跌倒报警手表", "一键 SOS、跌倒自动呼救、实时定位，子女远程查看", 459.00, 110, 1, "elderly smartwatch with sos button product photography"),
        ("指夹式血氧仪", "医用级精度、OLED 双向显示、8 秒出结果", 89.00, 300, 1, "fingertip pulse oximeter device product photography"),
        ("上臂式电子血压计", "一键测量、双人 240 组记忆、语音播报大字屏", 259.00, 130, 1, "digital blood pressure monitor for upper arm product photography"),
        ("智能体脂秤", "双频八电极、25 项身体数据、WiFi 自动同步", 199.00, 170, 1, "smart body fat scale with glass surface product photography"),
        ("智能跳绳（计数款）", "蓝牙同步、卡路里统计、可拆绳长，居家燃脂首选", 69.00, 0, 0, "smart jump rope with counter handle product photography"),
    ],
    "手机通讯": [
        ("5G 智能手机 X10", "6.7 寸 120Hz 直屏、5000mAh、6400 万三摄，8+256G", 1999.00, 100, 1, "modern smartphone with triple camera product photography"),
        ("竖向折叠屏手机", "轻折无痕、外屏快捷操作、铰链 40 万次寿命", 5499.00, 40, 1, "foldable smartphone half open product photography"),
        ("老人功能机 大字版", "4G 全网通、超大字体、长待机 30 天、一键亲情号", 199.00, 250, 1, "simple senior phone with big buttons product photography"),
        ("民用对讲机", "5W 大功率、5 公里通话、Type-C 充电，户外自驾组队", 189.00, 90, 1, "pair of handheld walkie talkies product photography"),
        ("随身 WiFi 路由器", "三网切换、1500G 月流量、8 台设备共享，差旅必备", 299.00, 140, 1, "pocket portable wifi router device product photography"),
        ("半导体手机散热背夹", "磁吸制冷、静音风道、APP 调温，游戏不掉帧", 129.00, 200, 1, "phone cooling fan attachment product photography"),
        ("手机防水袋", "IPX8 认证、双层密封、触屏灵敏，漂流游泳可用", 25.90, 600, 1, "waterproof phone pouch with lanyard product photography"),
        ("桌面手机三脚架", "铝合金材质、360° 旋转、冷靴拓展，直播拍摄通用", 79.00, 260, 1, "small desktop phone tripod product photography"),
        ("手游拉伸手柄", "蓝牙直连、霍尔摇杆、六指联动，吃鸡王者通用", 159.00, 180, 1, "mobile gaming controller grip product photography"),
        ("防窥钢化膜", "45° 防窥、9H 硬度、无白边，隐私出行更安心", 39.00, 0, 0, "privacy screen protector tempered glass product photography"),
    ],
    "电脑办公": [
        ("轻薄笔记本 14 寸", "2.8K OLED、12 代酷睿、1.3kg 机身，16G+512G", 5499.00, 50, 1, "silver slim laptop computer open on desk product photography"),
        ("无线静音键鼠套装", "2.4G 双模、静音微动、1600DPI，办公不扰人", 149.00, 220, 1, "wireless keyboard and mouse combo product photography"),
        ("27 寸 4K 显示器", "IPS 广色域、Type-C 90W 反充、升降旋转底座", 1899.00, 70, 1, "27 inch 4k computer monitor on stand product photography"),
        ("人体工学无线鼠标", "静音按键、三档 DPI、 USB-C 快充，久握不累", 119.00, 300, 1, "ergonomic wireless mouse product photography"),
        ("笔记本铝合金支架", "六档调节、镂空散热、折叠便携，适配 10-17 寸", 99.00, 260, 1, "aluminum laptop stand riser product photography"),
        ("家用喷墨打印机", "无线直连、自动双面、手机一键打印，学生家庭两用", 799.00, 60, 1, "compact inkjet printer on white background product photography"),
        ("1TB 移动固态硬盘", "读速 1050MB/s、Type-C 口、抗震金属壳", 459.00, 150, 1, "portable ssd drive metallic product photography"),
        ("128G 高速 U 盘", "USB3.2 双接口、读写 200MB/s，手机电脑互传", 69.00, 400, 1, "usb flash drive dual connector product photography"),
        ("1080P 高清摄像头", "自动对焦、内置麦克风、隐私盖，网课会议清晰", 189.00, 180, 1, "webcam camera with privacy cover product photography"),
        ("便携数码扫描仪", "A4 幅面、OCR 文字识别、一键生成 PDF，档案电子化", 699.00, 0, 0, "portable document scanner device product photography"),
    ],
    "家用电器": [
        ("1.5 匹变频空调", "新一级能效、自清洁、静音 18dB，冷暖两用", 2699.00, 60, 1, "white wall mounted air conditioner product photography"),
        ("10kg 滚筒洗衣机", "洗烘一体、除菌螨、变频直驱，母婴级洁净", 3299.00, 40, 1, "front load washing machine white product photography"),
        ("218L 双门冰箱", "风冷无霜、母婴专区、一级能效，小户型首选", 1899.00, 45, 1, "double door refrigerator silver product photography"),
        ("激光扫地机器人", "自动集尘、激光导航、拖扫一体，APP 远程操控", 1999.00, 80, 1, "robotic vacuum cleaner on floor product photography"),
        ("桌面空气净化器", "HEPA 13 滤网、除甲醛、睡眠级静音，卧室书房适用", 699.00, 120, 1, "small air purifier white product photography"),
        ("4L 智能电饭煲", "IH 加热、柴火饭模式、24h 预约，4 人家庭容量", 499.00, 100, 1, "rice cooker with digital panel product photography"),
        ("变频微波炉", "平板加热、一键解冻、智能菜单，厨房快手利器", 599.00, 90, 1, "modern microwave oven stainless steel product photography"),
        ("负离子电吹风", "11 万转高速、恒温护发、轻至 399g，速干不伤发", 399.00, 160, 1, "high speed hair dryer product photography"),
        ("声波电动牙刷", "磁悬浮马达、五种模式、IPX7 防水，续航 60 天", 199.00, 240, 1, "electric toothbrush with charging base product photography"),
        ("除螨吸尘仪", "紫外线除螨、拍打吸合一、热风除湿，床铺深层清洁", 459.00, 0, 0, "handheld vacuum mite remover product photography"),
    ],
    "家居家具": [
        ("实木伸缩餐桌", "岩板台面、可延展至 1.6m，四到六人用餐无压力", 1599.00, 30, 1, "wooden extendable dining table product photography"),
        ("三人位布艺沙发", "高回弹海绵、可拆洗外套、实木框架，客厅搭配款", 2999.00, 25, 1, "fabric three seat sofa product photography"),
        ("1.8m 记忆棉床垫", "双面睡感、独立袋装弹簧、静音抗干扰", 2199.00, 35, 1, "memory foam mattress on bed frame product photography"),
        ("书桌电脑桌", "1.2m 加厚桌面、走线槽、钢架稳固，居家办公位", 599.00, 80, 1, "simple wooden computer desk product photography"),
        ("五层开放式书架", "钢木结构、承重 30kg/层，客厅书房收纳", 359.00, 90, 1, "five tier open bookshelf product photography"),
        ("三门衣柜", "隐藏拉手、分区挂放、防潮背板，卧室大容量收纳", 1899.00, 20, 1, "three door wardrobe white product photography"),
        ("翻斗式鞋柜", "超薄 17cm、三层翻斗、防尘封闭，玄关省空间", 429.00, 70, 1, "slim shoe cabinet for entryway product photography"),
        ("岩板茶几", "80cm 圆角、耐高温岩板、金属腿，客厅轻奢风", 899.00, 55, 1, "modern round coffee table product photography"),
        ("北欧床头柜", "双抽屉、实木腿、圆角防撞，卧室收纳小帮手", 299.00, 100, 1, "nordic nightstand with two drawers product photography"),
        ("护眼落地灯", "无频闪、三档色温、遥控调光，客厅阅读角必备", 459.00, 0, 0, "modern floor lamp with warm light product photography"),
    ],
    "厨房用具": [
        ("麦饭石不粘炒锅", "32cm 大容量、无油烟、电磁炉燃气通用", 159.00, 200, 1, "non stick frying pan black product photography"),
        ("家用刀具五件套", "德国钢锻打、木柄防霉、含刀座，切菜切肉分开", 299.00, 150, 1, "kitchen knife set with block product photography"),
        ("316 不锈钢电热水壶", "1.7L 双层防烫、5 分钟速沸、自动断电", 129.00, 260, 1, "stainless steel electric kettle product photography"),
        ("多功能破壁机", "加热免滤、静音降噪、一键清洗，早餐豆浆米糊", 599.00, 110, 1, "high speed blender for smoothies product photography"),
        ("5L 空气炸锅", "360° 热风循环、无油低脂、可视窗口，炸烤一体", 399.00, 130, 1, "air fryer black with basket product photography"),
        ("半自动意式咖啡机", "20Bar 压力、奶泡打发、不锈钢机身，居家咖啡馆", 1299.00, 45, 1, "espresso coffee machine stainless product photography"),
        ("陶瓷餐具十六件套", "高温釉下彩、微波炉洗碗机可用，四人食配置", 259.00, 120, 1, "ceramic dinnerware set on table product photography"),
        ("500ml 保温杯", "316 内胆、12 小时保温、一键弹盖，办公通勤款", 99.00, 350, 1, "insulated stainless water bottle product photography"),
        ("厨房落地收纳架", "四层可调、带轮移动、304 不锈钢防水防锈", 199.00, 140, 1, "kitchen storage rack with wheels product photography"),
        ("硅胶铲勺六件套", "食品级硅胶、耐高温 230℃、不伤锅具涂层", 79.00, 0, 0, "silicone kitchen utensil set product photography"),
    ],
    "食品生鲜": [
        ("五常大米 5kg", "当季新米、稻花香 2 号、真空锁鲜，蒸煮清香回甘", 89.00, 300, 1, "bag of white rice product photography"),
        ("特级初榨橄榄油 1L", "冷榨工艺、酸度 0.3、凉拌煎炒通用", 128.00, 200, 1, "bottle of extra virgin olive oil product photography"),
        ("阿克苏冰糖心苹果 5 斤", "新疆直发、脆甜多汁、带糖心纹路，坏果包赔", 49.90, 400, 1, "fresh red apples in box product photography"),
        ("云南小粒咖啡豆 500g", "中深烘焙、手冲意式两用、现磨现喝", 79.00, 220, 1, "coffee beans in kraft bag product photography"),
        ("内蒙羔羊肉卷 500g", "草原散养、涮火锅专用、-18℃ 冷链直发", 69.00, 180, 1, "frozen sliced lamb meat product photography"),
        ("冰鲜三文鱼中段 300g", "挪威进口、刺身级、富含 Omega-3，冷链空运", 118.00, 120, 1, "fresh salmon fillet on ice product photography"),
        ("有机时蔬礼盒", "当季八种蔬菜、无农药残留、产地直采", 99.00, 150, 1, "box of fresh organic vegetables product photography"),
        ("进口全脂牛奶 1L*12", "新西兰原装、3.6g 蛋白、整箱囤货装", 159.00, 160, 1, "cartons of milk in a pack product photography"),
        ("每日坚果混合装 30 包", "六种果仁+三种果干、独立小袋，办公室零食", 99.00, 250, 1, "mixed nuts and dried fruit pack product photography"),
        ("深山土蜂蜜 500g", "中华蜂采集、波美度 42、结晶细腻，冲水调味", 88.00, 0, 0, "jar of natural honey product photography"),
    ],
    "服饰鞋包": [
        ("纯棉圆领短袖 T 恤", "260g 重磅、水洗不变形、五色可选，男女同款", 69.00, 500, 1, "plain cotton t-shirt folded product photography"),
        ("直筒水洗牛仔裤", "微弹面料、显瘦版型、不褪色，四季百搭", 199.00, 300, 1, "blue denim jeans product photography"),
        ("三合一冲锋衣", "防风防水 8000mm、抓绒内胆可拆卸，徒步通勤", 499.00, 150, 1, "waterproof hiking jacket product photography"),
        ("轻量缓震跑鞋", "超临界发泡中底、透气网面、单只 210g", 329.00, 200, 1, "running sneaker side view product photography"),
        ("真皮商务皮鞋", "头层牛皮、软底不磨脚、正装婚礼通用", 459.00, 120, 1, "black leather dress shoes product photography"),
        ("15.6 寸双肩背包", "防泼水、独立电脑仓、USB 充电口，通勤出差", 259.00, 220, 1, "black laptop backpack product photography"),
        ("帆布托特包", "加厚 16 安帆布、大容量 A4 可装，日常逛街款", 89.00, 300, 1, "canvas tote bag product photography"),
        ("山羊绒混纺围巾", "柔软亲肤、格纹百搭、礼盒包装，送长辈首选", 159.00, 180, 1, "plaid wool scarf folded product photography"),
        ("偏光太阳镜", "UV400、偏光防眩光、轻钛镜架，开车钓鱼适用", 199.00, 160, 1, "polarized sunglasses product photography"),
        ("棒球帽（刺绣款）", "纯棉水洗、后扣可调、硬挺不塌，遮阳凹造型", 79.00, 0, 0, "baseball cap with embroidery product photography"),
    ],
    "美妆个护": [
        ("氨基酸洁面乳", "弱酸性配方、温和清洁不紧绷，敏感肌可用", 79.00, 400, 1, "facial cleanser tube product photography"),
        ("烟酰胺保湿精华", "5% 浓度、提亮修护、轻薄好吸收，30ml 装", 199.00, 260, 1, "serum dropper bottle skincare product photography"),
        ("清爽防晒霜 SPF50+", "PA++++、不泛白不搓泥、防水抗汗，通勤户外", 129.00, 300, 1, "sunscreen bottle on white background product photography"),
        ("丝绒哑光口红", "雾面不拔干、显白三色可选、持久不沾杯", 159.00, 240, 1, "matte lipstick product photography"),
        ("持妆粉底液", "轻薄遮瑕、控油 8 小时、多色号可选", 229.00, 180, 1, "liquid foundation bottle product photography"),
        ("控油蓬松洗发水", "无硅油、氨基酸表活、洗后蓬松不塌，油头救星", 89.00, 320, 1, "shampoo bottle product photography"),
        ("氨基酸沐浴露", "温和低敏、泡沫绵密、洗后不假滑，家庭装 1L", 79.00, 280, 1, "body wash bottle product photography"),
        ("玻尿酸补水面膜 10 片", "三重玻尿酸、0.1mm 天丝膜布，急救补水", 99.00, 350, 1, "sheet mask package product photography"),
        ("淡香水 50ml", "前调柑橘尾调木质、留香 6 小时，男女通用", 299.00, 140, 1, "perfume bottle elegant product photography"),
        ("三刀头电动剃须刀", "浮动贴面、IPX7 水洗、Type-C 快充，出差便携", 259.00, 0, 0, "electric shaver with three blades product photography"),
    ],
}

IMG_API = "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image"


def image_url(prompt: str) -> str:
    return f"{IMG_API}?prompt={urllib.parse.quote(prompt, safe='')}&image_size=square_hd"


def esc(v: str) -> str:
    return str(v).replace("\\", "\\\\").replace("'", "''")


def main() -> None:
    rows = []
    pid = 5  # 1~4 为 schema.sql 自带示例商品
    for category, items in CATEGORIES.items():
        for name, desc, price, stock, status, prompt in items:
            rows.append(
                "({pid}, '{name}', '{desc}', {price}, {stock}, {status}, '{cat}', '{img}')".format(
                    pid=pid,
                    name=esc(name),
                    desc=esc(desc),
                    price=f"{price:.2f}",
                    stock=stock,
                    status=status,
                    cat=esc(category),
                    img=image_url(prompt),
                )
            )
            pid += 1

    out_dir = os.path.join("src", "main", "resources", "sql")
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "product-seed-100.sql")

    sql = []
    sql.append("-- 商品表种子数据：10 个分类 x 10 款 = 100 条（id 5~104）")
    sql.append("-- 由 tools/generate_product_seed.py 生成，可直接重复导入（INSERT IGNORE，按主键去重）")
    sql.append("-- 导入命令：mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p ecommerce < src/main/resources/sql/product-seed-100.sql")
    sql.append("")
    sql.append("USE `ecommerce`;")
    sql.append("SET NAMES utf8mb4;")
    sql.append("")
    sql.append("INSERT IGNORE INTO `product`")
    sql.append("    (`id`, `name`, `description`, `price`, `stock`, `status`, `category`, `image_url`)")
    sql.append("VALUES")
    sql.append(",\n".join(rows) + ";")
    sql.append("")
    sql.append("-- 校验：SELECT category, COUNT(*) FROM product WHERE deleted = 0 GROUP BY category;")
    sql.append("")

    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(sql))

    print(f"generated {len(rows)} rows -> {out_path}")


if __name__ == "__main__":
    main()
