<?php
declare(strict_types=1);

/**
 * 查询当前自助赞助金额（内部接口，机器人用）。
 * GET/POST：key
 */
require __DIR__ . '/lib.php';

require_internal_key();

json_out(['ok' => true, 'money' => current_money()]);